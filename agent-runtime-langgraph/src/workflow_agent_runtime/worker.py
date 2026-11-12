"""Bounded whole-graph worker, with no node queue and no implicit retry loop.

Deployment supplies an authenticated dispatch client with bounded RPC deadlines.
An in-flight synchronous call occupies one slot and one checkpoint connection;
threads are deliberately bounded rather than described as asynchronous I/O.
"""

import threading
import time
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
from contextlib import AbstractContextManager
from typing import Protocol

from langgraph.checkpoint.base import BaseCheckpointSaver
from pydantic import Field

from .contracts import (
    Contract,
    ExecutionSession,
    GovernedGateway,
    ResumeSignal,
    RunBinding,
    RuntimeContractError,
    RuntimeView,
    SliceBudget,
)
from .graph import GovernedAgentGraph


class GraphClaim(Contract):
    command_id: str
    binding: RunBinding
    session: ExecutionSession = Field(repr=False)
    resume: ResumeSignal | None = None
    lease_expires_epoch_ms: int = Field(default=0, ge=0)


class DispatchClient(Protocol):
    """Trusted whole-graph protocol. Implementations must bound every RPC duration."""

    def claim_next(self) -> GraphClaim | None: ...
    def renew(self, claim: GraphClaim) -> bool: ...
    def defer(self, claim: GraphClaim, view: RuntimeView) -> bool: ...
    def acknowledge(self, claim: GraphClaim, view: RuntimeView) -> bool: ...


class _FencedGateway:
    def __init__(self, delegate: GovernedGateway, lost: threading.Event):
        self.delegate, self.lost = delegate, lost

    def authorize(self, binding, session):
        if self.lost.is_set():
            raise RuntimeContractError("GRAPH_LEASE_LOST")
        self.delegate.authorize(binding, session)

    def invoke(self, binding, session, request):
        self.authorize(binding, session)
        result = self.delegate.invoke(binding, session, request)
        self.authorize(binding, session)
        return result


class BoundedGraphWorker:
    """poll is serialized; at most slots futures, sessions, and DB connections.

    Caller must poll more frequently than lease expiry. Renewal failure fences
    new calls locally; it never cancels/reclaims an existing remote request.
    Failed handoff is recovered via the durable command and latest checkpoint.
    """

    def __init__(
        self,
        *,
        slots: int,
        dispatch: DispatchClient,
        gateway: GovernedGateway,
        checkpoint: Callable[[RunBinding], AbstractContextManager[BaseCheckpointSaver]],
        artifact_digest: str,
        slice_budget: SliceBudget | None = None,
        renew_interval_seconds: float = 10,
        clock_ms: Callable[[], int] = lambda: time.time_ns() // 1_000_000,
    ):
        if not 1 <= slots <= 32 or not 0 < renew_interval_seconds <= 30:
            raise ValueError("invalid bounded worker configuration")
        self.slots, self.dispatch, self.gateway = slots, dispatch, gateway
        self.checkpoint, self.artifact_digest = checkpoint, artifact_digest
        self.slice_budget, self.clock_ms = slice_budget or SliceBudget(), clock_ms
        self.renew_interval = renew_interval_seconds
        self.executor = ThreadPoolExecutor(max_workers=slots, thread_name_prefix="graph-slice")
        self.jobs: dict[Future, tuple[GraphClaim, threading.Event, float]] = {}
        self.lock = threading.Lock()
        self.closed = False

    def poll(self) -> list[str]:
        """One bounded scan. Return sanitized outcomes, never tokens or raw exceptions."""
        outcomes = []
        with self.lock:
            if self.closed:
                return outcomes
            due = [
                (f, item)
                for f, item in self.jobs.items()
                if not f.done() and time.monotonic() - item[2] >= self.renew_interval
            ]
            if due and hasattr(self.dispatch, "renew_batch"):
                try:
                    renewed = self.dispatch.renew_batch([item[0] for _, item in due])
                    if len(renewed) != len(due):
                        raise RuntimeContractError("INVALID_RENEWAL_BATCH")
                except Exception:
                    renewed = [False] * len(due)
                for (future, (claim, lost, _)), accepted in zip(due, renewed, strict=True):
                    if not accepted:
                        lost.set()
                    self.jobs[future] = (claim, lost, time.monotonic())
            for future, (claim, lost, renewed_at) in list(self.jobs.items()):
                if future.done():
                    del self.jobs[future]
                    try:
                        outcomes.append(future.result())
                    except Exception:
                        outcomes.append("RECOVERY_REQUIRED")
                elif time.monotonic() - renewed_at >= self.renew_interval:
                    try:
                        if not self.dispatch.renew(claim):
                            lost.set()
                    except Exception:
                        lost.set()
                    self.jobs[future] = (claim, lost, time.monotonic())
            # At most one bounded claim RPC per poll; filling a large pool cannot starve renewal.
            for _ in range(min(1, self.slots - len(self.jobs))):
                claim = self.dispatch.claim_next()
                if claim is None:
                    break
                lost = threading.Event()
                future = self.executor.submit(self._execute, claim, lost)
                self.jobs[future] = (claim, lost, time.monotonic())
        return outcomes

    def _execute(self, claim: GraphClaim, lost: threading.Event) -> str:
        with self.checkpoint(claim.binding) as saver:
            graph = GovernedAgentGraph(
                binding=claim.binding,
                session=claim.session,
                gateway=_FencedGateway(self.gateway, lost),
                checkpointer=saver,
                artifact_digest=self.artifact_digest,
                slice_budget=self.slice_budget,
                clock_ms=self.clock_ms,
            )
            # Lost defer ACK may leave a newer wait than the command carried.
            # Read the latest authority; never try to resume a historical checkpoint.
            snapshot = graph.graph.get_state(graph.config)
            waiting = snapshot.values.get("waiting") if snapshot.values else None
            if waiting and waiting["not_before_epoch_ms"] <= self.clock_ms():
                view = graph.advance(
                    ResumeSignal(
                        checkpoint_id=snapshot.config["configurable"]["checkpoint_id"],
                        wait_id=waiting["wait_id"],
                    )
                )
            else:
                view = graph.advance()
        # Release session lock/connection BEFORE allowing the next generation to claim.
        if lost.is_set():
            return "LEASE_LOST"
        if view.status == "WAITING":
            accepted = self.dispatch.defer(claim, view)
        else:
            accepted = self.dispatch.acknowledge(claim, view)
        return view.status if accepted else "RECOVERY_REQUIRED"

    def close(self) -> None:
        with self.lock:
            self.closed = True
            for _, lost, _ in self.jobs.values():
                lost.set()
        # No thread-kill fiction: an already in-flight RPC retains its slot until it returns.
        self.executor.shutdown(wait=True)

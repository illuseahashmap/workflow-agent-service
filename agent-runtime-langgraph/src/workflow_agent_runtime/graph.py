"""LangGraph owns control flow; the platform owns every external invocation."""

import json
import time
from collections.abc import Callable
from typing import Any, TypedDict

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from langsmith import tracing_context

from .contracts import (
    Deferred,
    ExecutionSession,
    GovernedGateway,
    InvocationReceipt,
    InvocationRequest,
    ResumeSignal,
    RunBinding,
    RuntimeContractError,
    RuntimeView,
    ToolCall,
)


class GraphState(TypedDict, total=False):
    binding: dict[str, Any]
    model_calls: int
    tool_calls: int
    tool_queue: list[dict[str, Any]]
    receipt_refs: list[str]
    pending: dict[str, Any]
    waiting: dict[str, Any] | None
    candidate_ref: str | None
    validation_ref: str | None
    status: str


class GovernedAgentGraph:
    """One authorized, exclusively owned invocation of a fixed graph artifact.

    Production callers MUST use exclusive_postgres_checkpoint; an arbitrary
    saver alone does not serialize concurrent invocations of a thread. No native
    v5 checkpoint, Java nextStep, implicit retry, or Flowable call is used here.
    """

    def __init__(
        self,
        *,
        binding: RunBinding,
        session: ExecutionSession,
        gateway: GovernedGateway,
        checkpointer: BaseCheckpointSaver,
        artifact_digest: str,
        clock_ms: Callable[[], int] = lambda: time.time_ns() // 1_000_000,
    ) -> None:
        if binding.graph_artifact_digest != artifact_digest:
            raise RuntimeContractError("GRAPH_ARTIFACT_MISMATCH")
        self.binding = binding
        self.session = session
        self.gateway = gateway
        self.clock_ms = clock_ms
        self.config = {
            "configurable": {"thread_id": binding.thread_id},
            "recursion_limit": 8 * (binding.max_model_calls + binding.max_tool_calls) + 32,
        }
        builder = StateGraph(GraphState)
        builder.add_node("prepare_model", self._prepare_model)
        builder.add_node("prepare_tool", self._prepare_tool)
        builder.add_node("prepare_validation", self._prepare_validation)
        builder.add_node("invoke_gateway", self._invoke_gateway)
        builder.add_node("wait_for_capacity", self._wait)
        builder.add_edge(START, "prepare_model")
        for node in ("prepare_model", "prepare_tool", "prepare_validation", "wait_for_capacity"):
            builder.add_edge(node, "invoke_gateway")
        builder.add_conditional_edges("invoke_gateway", self._route)
        self.graph = builder.compile(checkpointer=checkpointer)

    def _authorize(self) -> None:
        if self.clock_ms() >= self.binding.deadline_epoch_ms:
            raise RuntimeContractError("RUN_DEADLINE_EXPIRED")
        self.gateway.authorize(self.binding, self.session)

    def _guard(self, state: GraphState) -> None:
        if state.get("binding") != self.binding.model_dump(mode="json"):
            raise RuntimeContractError("CHECKPOINT_BINDING_MISMATCH")
        self._authorize()

    def advance(self, resume: ResumeSignal | None = None) -> RuntimeView:
        """Idempotent entry: resume the latest graph state, never replay a historical one.

        A repeated submit on a completed thread returns references without redoing
        model/tool work. Java still revalidates before accepting the business result.
        """
        # LangSmith is a transitive OSS SDK dependency, not our tracing backend.
        # An inherited developer environment must not silently export graph data.
        with tracing_context(enabled=False):
            return self._advance(resume)

    def _advance(self, resume: ResumeSignal | None) -> RuntimeView:
        self._authorize()
        snapshot = self.graph.get_state(self.config)
        if not snapshot.values:
            if resume is not None:
                raise RuntimeContractError("WAIT_NOT_FOUND")
            graph_input = {
                "binding": self.binding.model_dump(mode="json"),
                "model_calls": 0,
                "tool_calls": 0,
                "tool_queue": [],
                "receipt_refs": [],
                "waiting": None,
                "status": "RUNNING",
            }
        else:
            self._guard(snapshot.values)
            interrupts = [item for task in snapshot.tasks for item in task.interrupts]
            if resume is not None:
                checkpoint_id = snapshot.config["configurable"]["checkpoint_id"]
                if (
                    len(interrupts) != 1
                    or checkpoint_id != resume.checkpoint_id
                    or interrupts[0].value.get("wait_id") != resume.wait_id
                ):
                    raise RuntimeContractError("STALE_RESUME")
                wait = Deferred.model_validate(snapshot.values["waiting"])
                if self.clock_ms() < wait.not_before_epoch_ms:
                    raise RuntimeContractError("WAIT_NOT_ELIGIBLE")
                graph_input = Command(resume={"wait_id": resume.wait_id})
            elif interrupts or not snapshot.next:
                return self._view(snapshot)
            else:
                graph_input = None
        # Intent and candidate nodes MUST commit before the next external action.
        self.graph.invoke(graph_input, config=self.config, durability="sync")
        final = self.graph.get_state(self.config)
        self._guard(final.values)
        return self._view(final)

    def _view(self, snapshot: Any) -> RuntimeView:
        state = snapshot.values
        waiting = state.get("waiting")
        status = "WAITING" if waiting else state["status"]
        return RuntimeView(
            thread_id=self.binding.thread_id,
            checkpoint_id=snapshot.config["configurable"]["checkpoint_id"],
            status=status,
            wait=Deferred.model_validate(waiting) if waiting else None,
            candidate_ref=state.get("candidate_ref"),
            validation_ref=state.get("validation_ref"),
        )

    @staticmethod
    def _request(state: GraphState) -> InvocationRequest:
        # JSON mode restores strict tuples without permissive coercion of IDs/types.
        return InvocationRequest.model_validate_json(json.dumps(state["pending"]))

    def _prepare_model(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        count = state["model_calls"] + 1
        if count > self.binding.max_model_calls:
            raise RuntimeContractError("MODEL_STEP_BUDGET_EXHAUSTED")
        request = InvocationRequest(
            kind="MODEL",
            operation_id=f"model:{count}",
            input_refs=(self.binding.input_ref, *state["receipt_refs"]),
        )
        return {"model_calls": count, "pending": request.model_dump(mode="json")}

    def _prepare_tool(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        count = state["tool_calls"] + 1
        if count > self.binding.max_tool_calls:
            raise RuntimeContractError("TOOL_STEP_BUDGET_EXHAUSTED")
        tool = ToolCall.model_validate(state["tool_queue"][0])
        request = InvocationRequest(
            kind="TOOL", operation_id=f"tool:{count}", input_refs=(), tool=tool
        )
        return {"tool_calls": count, "pending": request.model_dump(mode="json")}

    def _prepare_validation(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = InvocationRequest(
            kind="VALIDATE",
            operation_id="validate:1",
            input_refs=(state["candidate_ref"], *state["receipt_refs"]),
        )
        return {"pending": request.model_dump(mode="json")}

    def _invoke_gateway(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = self._request(state)
        result = self.gateway.invoke(self.binding, self.session, request)
        # Recheck after I/O: timeout/revocation cannot become a successful checkpoint.
        self._authorize()
        if isinstance(result, Deferred):
            return {"waiting": result.model_dump(mode="json")}
        if not isinstance(result, InvocationReceipt) or (
            result.binding_digest != self.binding.digest
            or result.operation_id != request.operation_id
            or result.request_digest != request.digest
        ):
            raise RuntimeContractError("RECEIPT_BINDING_MISMATCH")
        update: dict[str, Any] = {
            "waiting": None,
            "receipt_refs": [*state["receipt_refs"], result.receipt_ref],
        }
        if request.kind == "MODEL":
            if result.decision is None or result.accepted is not None:
                raise RuntimeContractError("INVALID_MODEL_RECEIPT")
            update["tool_queue"] = [tool.model_dump(mode="json") for tool in result.decision.tools]
            update["candidate_ref"] = result.decision.candidate_ref
        elif request.kind == "TOOL":
            if result.decision is not None or result.accepted is not None:
                raise RuntimeContractError("INVALID_TOOL_RECEIPT")
            update["tool_queue"] = state["tool_queue"][1:]
        else:
            if result.accepted is None or result.decision is not None:
                raise RuntimeContractError("INVALID_VALIDATION_RECEIPT")
            update["validation_ref"] = result.receipt_ref
            update["status"] = "CANDIDATE_READY" if result.accepted else "REJECTED"
        return update

    def _wait(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        interrupt(state["waiting"])
        self._authorize()
        return {"waiting": None}

    @staticmethod
    def _route(state: GraphState) -> str:
        if state.get("waiting"):
            return "wait_for_capacity"
        kind = state["pending"]["kind"]
        if kind == "VALIDATE":
            return END
        if state["tool_queue"]:
            return "prepare_tool"
        if kind == "MODEL":
            return "prepare_validation"
        return "prepare_model"

"""LangGraph owns control flow; the platform owns every external invocation."""

import json
import time
import uuid
from collections.abc import Callable
from typing import Any, TypedDict

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from langsmith import tracing_context

from .contracts import (
    ContextRequired,
    CorrectionRequired,
    Deferred,
    ExecutionSession,
    FormatRepairRequired,
    GovernedGateway,
    InvocationReceipt,
    InvocationRequest,
    PlanRequired,
    ReplanRequired,
    ResumeSignal,
    RouteRepairRequired,
    RouteRequired,
    RunBinding,
    RuntimeContractError,
    RuntimeStopped,
    RuntimeView,
    SliceBudget,
    ToolCall,
    fingerprint,
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
    route_receipt_ref: str | None
    plan_receipt_ref: str | None
    plan_revision: int
    memory_ref: str | None
    read_ref: str | None
    memory_enabled: bool
    context_resume: dict[str, Any] | None
    read_calls: int
    correction_id: str
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
        slice_budget: SliceBudget | None = None,
        monotonic_ms: Callable[[], int] = lambda: time.monotonic_ns() // 1_000_000,
    ) -> None:
        if binding.graph_artifact_digest != artifact_digest:
            raise RuntimeContractError("GRAPH_ARTIFACT_MISMATCH")
        self.binding = binding
        self.session = session
        self.gateway = gateway
        self.clock_ms = clock_ms
        self.slice_budget = slice_budget or SliceBudget()
        self.monotonic_ms = monotonic_ms
        self.slice_calls = 0
        self.slice_started = 0
        self.config = {
            "configurable": {"thread_id": binding.thread_id},
            "recursion_limit": 8 * (binding.max_model_calls + binding.max_tool_calls) + 32,
        }
        builder = StateGraph(GraphState)
        builder.add_node("prepare_summary", self._prepare_summary)
        builder.add_node("prepare_context_resume", self._prepare_context_resume)
        builder.add_node("prepare_read", self._prepare_read)
        builder.add_node("prepare_format_repair", self._prepare_format_repair)
        builder.add_node("prepare_route", self._prepare_route)
        builder.add_node("prepare_route_validation", self._prepare_route_validation)
        builder.add_node("prepare_route_repair", self._prepare_correction)
        builder.add_node("prepare_plan", self._prepare_plan)
        builder.add_node("prepare_plan_validation", self._prepare_plan_validation)
        builder.add_node("prepare_correction", self._prepare_correction)
        builder.add_node("prepare_model", self._prepare_model)
        builder.add_node("prepare_tool", self._prepare_tool)
        builder.add_node("prepare_validation", self._prepare_validation)
        builder.add_node("invoke_gateway", self._invoke_gateway)
        builder.add_node("wait_for_capacity", self._wait)
        builder.add_edge(START, "prepare_model")
        for node in (
            "prepare_summary",
            "prepare_context_resume",
            "prepare_read",
            "prepare_format_repair",
            "prepare_model",
            "prepare_route",
            "prepare_route_validation",
            "prepare_route_repair",
            "prepare_plan",
            "prepare_plan_validation",
            "prepare_correction",
            "prepare_tool",
            "prepare_validation",
            "wait_for_capacity",
        ):
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
            try:
                return self._advance(resume)
            except RuntimeStopped:
                if self.binding.state_schema_version != 2:
                    raise
                snapshot = self.graph.get_state(self.config)
                if snapshot.values and snapshot.values.get("binding") != self.binding.model_dump(
                    mode="json"
                ):
                    raise RuntimeContractError("CHECKPOINT_BINDING_MISMATCH") from None
                if snapshot.values.get("status") != "STOPPED":
                    self.graph.update_state(
                        self.config,
                        {
                            "binding": self.binding.model_dump(mode="json"),
                            "status": "STOPPED",
                            "waiting": None,
                            "candidate_ref": None,
                            "validation_ref": None,
                        },
                        as_node="invoke_gateway",
                    )
                return self._view(self.graph.get_state(self.config))

    def _advance(self, resume: ResumeSignal | None) -> RuntimeView:
        self.slice_calls = 0
        self.slice_started = self.monotonic_ms()
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

    def _model_refs(self, state: GraphState) -> tuple[str, ...]:
        selected = tuple(ref for ref in (state.get("memory_ref"), state.get("read_ref")) if ref)
        return (self.binding.input_ref, *state["receipt_refs"], *selected)

    def _prepare_summary(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        refs = self._model_refs(state)
        request = InvocationRequest(
            kind="MODEL",
            operation_id="summary:" + fingerprint([self.binding.digest, refs]),
            input_refs=refs,
        )
        return {
            "model_calls": state["model_calls"] + 1,
            "pending": request.model_dump(mode="json"),
            "status": "RUNNING",
        }

    def _prepare_context_resume(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        origin = state.get("context_resume")
        if origin is None:
            result = self._prepare_model(state)
        else:
            request = InvocationRequest.model_validate_json(
                json.dumps({**origin, "input_refs": self._model_refs(state)})
            )
            result = {
                "pending": request.model_dump(mode="json"),
                "model_calls": state["model_calls"] + 1,
                "status": "RUNNING",
            }
        return {**result, "context_resume": None, "candidate_ref": None}

    def _prepare_read(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        tool = ToolCall.model_validate(state["tool_queue"][0])
        request = InvocationRequest(
            kind="READ_RESULT",
            operation_id="read:" + tool.arguments_ref,
            input_refs=self._model_refs(state),
            tool=tool,
        )
        return {
            "pending": request.model_dump(mode="json"),
            "read_calls": state.get("read_calls", 0) + 1,
        }

    def _prepare_model(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        count = state["model_calls"] + 1
        if self.binding.state_schema_version == 1 and count > self.binding.max_model_calls:
            raise RuntimeContractError("MODEL_STEP_BUDGET_EXHAUSTED")
        request = InvocationRequest(
            kind="MODEL",
            operation_id=f"model:{count}"
            if self.binding.state_schema_version == 1
            else f"model:{uuid.uuid4()}",
            input_refs=self._model_refs(state),
        )
        return {
            "model_calls": count,
            "pending": request.model_dump(mode="json"),
            "status": "RUNNING",
        }

    def _prepare_route(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = InvocationRequest(
            kind="MODEL",
            operation_id="route:" + self.binding.digest,
            input_refs=self._model_refs(state),
        )
        return {
            "model_calls": state["model_calls"] + 1,
            "pending": request.model_dump(mode="json"),
            "status": "RUNNING",
        }

    def _prepare_route_validation(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = InvocationRequest(
            kind="VALIDATE",
            operation_id="route-validate:"
            + fingerprint([self.binding.digest, state["candidate_ref"]]),
            input_refs=(state["candidate_ref"], *state["receipt_refs"]),
        )
        return {"pending": request.model_dump(mode="json")}

    def _prepare_plan(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = InvocationRequest(
            kind="MODEL",
            operation_id="plan:" + self.binding.digest,
            input_refs=self._model_refs(state),
        )
        return {
            "model_calls": state["model_calls"] + 1,
            "pending": request.model_dump(mode="json"),
            "status": "RUNNING",
        }

    def _prepare_plan_validation(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = InvocationRequest(
            kind="VALIDATE",
            operation_id="plan-validate:"
            + fingerprint([self.binding.digest, state["candidate_ref"]]),
            input_refs=(state["candidate_ref"], *state["receipt_refs"]),
        )
        return {"pending": request.model_dump(mode="json")}

    def _prepare_format_repair(self, state: GraphState) -> dict[str, Any]:
        # Reuses the stable grant identity, counters and persisted causal inputs.
        return self._prepare_correction(state)

    def _prepare_correction(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = InvocationRequest(
            kind="MODEL",
            operation_id=state["correction_id"],
            input_refs=self._model_refs(state),
        )
        return {
            "model_calls": state["model_calls"] + 1,
            "pending": request.model_dump(mode="json"),
            "status": "RUNNING",
            "candidate_ref": None,
            "validation_ref": None,
        }

    def _prepare_tool(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        count = state["tool_calls"] + 1
        if self.binding.state_schema_version == 1 and count > self.binding.max_tool_calls:
            raise RuntimeContractError("TOOL_STEP_BUDGET_EXHAUSTED")
        tool = ToolCall.model_validate(state["tool_queue"][0])
        request = InvocationRequest(
            kind="TOOL",
            operation_id=f"tool:{count}"
            if self.binding.state_schema_version == 1
            else tool.arguments_ref,
            input_refs=(),
            tool=tool,
        )
        return {"tool_calls": count, "pending": request.model_dump(mode="json")}

    def _prepare_validation(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = InvocationRequest(
            kind="VALIDATE",
            operation_id="validate:1"
            if self.binding.state_schema_version == 1
            else "validate:" + fingerprint([self.binding.digest, state["candidate_ref"]]),
            input_refs=(state["candidate_ref"], *state["receipt_refs"]),
        )
        return {"pending": request.model_dump(mode="json")}

    def _invoke_gateway(self, state: GraphState) -> dict[str, Any]:
        self._guard(state)
        request = self._request(state)
        # Do not yield during I/O: commit its receipt first. Resume executes this
        # same pending intent, without incrementing logical operation counters.
        if self.slice_calls >= self.slice_budget.max_invocations or (
            self.slice_calls > 0
            and self.monotonic_ms() - self.slice_started >= self.slice_budget.max_elapsed_ms
        ):
            return {
                "waiting": Deferred(
                    wait_id=f"slice:{request.operation_id}:{len(state['receipt_refs'])}",
                    reason="SLICE",
                    not_before_epoch_ms=self.clock_ms(),
                ).model_dump(mode="json")
            }
        self.slice_calls += 1
        try:
            result = self.gateway.invoke(self.binding, self.session, request)
        except ContextRequired:
            if (
                self.binding.state_schema_version != 2
                or request.kind != "MODEL"
                or request.operation_id.startswith("summary:")
            ):
                raise
            return {
                "status": "CONTEXT_REQUIRED",
                "context_resume": request.model_dump(mode="json"),
                "memory_enabled": True,
                "model_calls": state["model_calls"] - 1,
                "waiting": None,
            }
        except RouteRequired:
            if self.binding.state_schema_version != 2 or request.kind != "MODEL":
                raise
            return {
                "status": "ROUTE_REQUIRED",
                "model_calls": state["model_calls"] - 1,
                "waiting": None,
            }
        except RouteRepairRequired:
            if (
                self.binding.state_schema_version != 2
                or request.kind != "VALIDATE"
                or not request.operation_id.startswith("route-validate:")
            ):
                raise
            return {
                "status": "ROUTE_REPAIR_REQUIRED",
                "correction_id": "route:repair:"
                + fingerprint([self.binding.digest, request.digest]),
                "tool_queue": [],
                "waiting": None,
            }
        except PlanRequired:
            if self.binding.state_schema_version != 2 or request.kind != "MODEL":
                raise
            return {
                "status": "PLAN_REQUIRED",
                "model_calls": state["model_calls"] - 1,
                "waiting": None,
            }
        except FormatRepairRequired:
            if self.binding.state_schema_version != 2 or request.kind != "VALIDATE":
                raise
            return {
                "status": "FORMAT_REPAIR_REQUIRED",
                "correction_id": "repair:" + fingerprint([self.binding.digest, request.digest]),
                "tool_queue": [],
                "waiting": None,
            }
        except (CorrectionRequired, ReplanRequired) as redirect:
            if self.binding.state_schema_version != 2 or request.kind != "TOOL":
                raise
            return {
                "status": "CORRECTION_REQUIRED",
                "correction_id": (
                    "plan:correction:" if isinstance(redirect, ReplanRequired) else "correction:"
                )
                + fingerprint([self.binding.digest, request.digest]),
                "tool_queue": [],
                "waiting": None,
            }
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
            if request.operation_id.startswith("summary:"):
                if (
                    result.decision.tools
                    or not result.decision.candidate_ref
                    or not result.decision.candidate_ref.startswith("memory:")
                ):
                    raise RuntimeContractError("INVALID_SUMMARY_RECEIPT")
                return {
                    **update,
                    "memory_ref": result.decision.candidate_ref,
                    "memory_enabled": True,
                    "status": "MEMORY_READY",
                    "candidate_ref": None,
                }
            update["tool_queue"] = [tool.model_dump(mode="json") for tool in result.decision.tools]
            update["candidate_ref"] = result.decision.candidate_ref
        elif request.kind == "READ_RESULT":
            if (
                result.accepted is not None
                or result.decision is None
                or result.decision.tools
                or not result.decision.candidate_ref
                or not result.decision.candidate_ref.startswith("read-context:")
            ):
                raise RuntimeContractError("INVALID_READ_RECEIPT")
            update["read_ref"] = result.decision.candidate_ref
            update["tool_queue"] = state["tool_queue"][1:]
        elif request.kind == "TOOL":
            if result.decision is not None or result.accepted is not None:
                raise RuntimeContractError("INVALID_TOOL_RECEIPT")
            update["tool_queue"] = state["tool_queue"][1:]
        else:
            if result.accepted is None or result.decision is not None:
                raise RuntimeContractError("INVALID_VALIDATION_RECEIPT")
            if request.operation_id.startswith("route-validate:") and result.accepted:
                return {
                    "waiting": None,
                    "route_receipt_ref": result.receipt_ref,
                    "candidate_ref": None,
                    "validation_ref": None,
                    "status": "ROUTE_ACCEPTED",
                }
            if request.operation_id.startswith("plan-validate:") and result.accepted:
                return {
                    "waiting": None,
                    "plan_receipt_ref": result.receipt_ref,
                    "plan_revision": state.get("plan_revision", 0) + 1,
                    "candidate_ref": None,
                    "validation_ref": None,
                    "status": "PLAN_ACCEPTED",
                }
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
        if state.get("status") == "STOPPED":
            return END
        if state.get("status") == "CONTEXT_REQUIRED":
            return "prepare_summary"
        if state.get("status") == "MEMORY_READY":
            return "prepare_context_resume"
        if state.get("status") == "ROUTE_REQUIRED":
            return "prepare_route"
        if state.get("status") == "ROUTE_REPAIR_REQUIRED":
            return "prepare_route_repair"
        if state.get("status") == "ROUTE_ACCEPTED":
            return "prepare_model"
        if state.get("status") == "PLAN_REQUIRED":
            return "prepare_plan"
        if state.get("status") == "PLAN_ACCEPTED":
            return "prepare_model"
        if state.get("status") == "FORMAT_REPAIR_REQUIRED":
            return "prepare_format_repair"
        if state.get("status") == "CORRECTION_REQUIRED":
            return "prepare_correction"
        if state.get("waiting"):
            return "wait_for_capacity"
        kind = state["pending"]["kind"]
        if kind == "VALIDATE":
            return END
        if state["tool_queue"]:
            return (
                "prepare_read"
                if state["tool_queue"][0]["tool_code"] == "graph_read_result"
                else "prepare_tool"
            )
        if kind == "TOOL" and state.get("memory_enabled"):
            return "prepare_summary"
        if kind == "MODEL":
            if state["pending"]["operation_id"].startswith("route:"):
                return "prepare_route_validation"
            if state["pending"]["operation_id"].startswith("plan:"):
                return "prepare_plan_validation"
            return "prepare_validation"
        return "prepare_model"

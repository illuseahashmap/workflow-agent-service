from contextlib import contextmanager

import pytest
from fake_gateway import InjectedCrash, LedgerGateway
from langgraph.checkpoint.sqlite import SqliteSaver
from langsmith import tracing_context
from langsmith.run_helpers import get_tracing_context
from pydantic import ValidationError

from workflow_agent_runtime.contracts import (
    ExecutionSession,
    ModelDecision,
    ResumeSignal,
    RuntimeContractError,
    SliceBudget,
)
from workflow_agent_runtime.graph import GovernedAgentGraph


@contextmanager
def runtime(path, binding, session, gateway, clock_ms=lambda: 1000, **options):
    with SqliteSaver.from_conn_string(str(path)) as saver:
        yield GovernedAgentGraph(
            binding=binding,
            session=session,
            gateway=gateway,
            checkpointer=saver,
            artifact_digest="a" * 64,
            clock_ms=clock_ms,
            **options,
        )


def test_slices_survive_cold_restarts_without_consuming_extra_operations(
    tmp_path, binding, session
):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    signal = None
    counts = []
    for _ in range(5):
        with runtime(
            tmp_path / "graph.db",
            binding,
            session,
            gateway,
            slice_budget=SliceBudget(max_invocations=1),
        ) as graph:
            view = graph.advance(signal)
            counts.append(gateway.count(binding))
            if view.status == "WAITING":
                assert view.wait.reason == "SLICE"
                assert graph.advance() == view
                signal = ResumeSignal(checkpoint_id=view.checkpoint_id, wait_id=view.wait.wait_id)
    assert view.status == "CANDIDATE_READY"
    assert counts == [1, 2, 3, 4, 5]


def test_elapsed_slice_yields_after_receipt_not_during_request(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    ticks = iter([0, 101])
    with runtime(
        tmp_path / "graph.db",
        binding,
        session,
        gateway,
        slice_budget=SliceBudget(max_invocations=16, max_elapsed_ms=100),
        monotonic_ms=lambda: next(ticks),
    ) as graph:
        view = graph.advance()
        assert view.status == "WAITING"
        assert gateway.count(binding) == 1
        snapshot = graph.graph.get_state(graph.config)
        assert len(snapshot.values["receipt_refs"]) == 1
        assert snapshot.values["pending"]["operation_id"] == "tool:1"


@pytest.mark.parametrize("crash", ["model:1", "tool:1", "tool:2", "model:2", "validate:1"])
def test_cold_recovery_reuses_durable_receipts(tmp_path, binding, session, crash):
    database, ledger = tmp_path / "graph.db", tmp_path / "ledger.db"
    gateway = LedgerGateway(ledger, crash=crash)
    with runtime(database, binding, session, gateway) as graph:
        with pytest.raises(InjectedCrash):
            graph.advance()
        pending = graph.graph.get_state(graph.config).values["pending"]
        assert pending["operation_id"] == crash  # intent committed before external action
    gateway = LedgerGateway(ledger)
    gateway.generation = 2
    successor = ExecutionSession(generation=2, claim_token="successor")
    with runtime(database, binding, successor, gateway) as graph:
        result = graph.advance()
        assert result.status == "CANDIDATE_READY"
        assert result.candidate_ref == f"candidate:{binding.run_id}"
        assert gateway.count(binding) == 5
        assert graph.advance() == result  # lost result ACK does not rerun
        assert gateway.count(binding) == 5


def test_two_same_tenant_runs_never_share_receipts(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    other = binding.model_copy(update={"run_id": 20002, "input_ref": "input:purchase:20002"})
    results = []
    for item in (binding, other):
        with runtime(tmp_path / "graph.db", item, session, gateway) as graph:
            results.append(graph.advance())
            assert gateway.count(item) == 5
    assert results[0].thread_id != results[1].thread_id
    assert results[0].candidate_ref != results[1].candidate_ref


@pytest.mark.parametrize(
    "field,value",
    [
        ("agent_version_id", 4),
        ("input_ref", "input:changed"),
        ("subject_ref", "subject:other"),
        ("max_model_calls", 4),
    ],
)
def test_binding_cannot_change_on_resume(tmp_path, binding, session, field, value):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        graph.advance()
    changed = binding.model_copy(update={field: value})
    with runtime(tmp_path / "graph.db", changed, session, gateway) as graph:
        with pytest.raises(RuntimeContractError, match="CHECKPOINT_BINDING_MISMATCH"):
            graph.advance()


def test_two_capacity_waits_and_stale_resume(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    gateway.defer = 2
    gateway.not_before = 2000
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        first = graph.advance()
        assert first.status == "WAITING"
        assert gateway.count(binding) == 0
        assert graph.advance() == first
        signal = ResumeSignal(checkpoint_id=first.checkpoint_id, wait_id=first.wait.wait_id)
        with pytest.raises(RuntimeContractError, match="WAIT_NOT_ELIGIBLE"):
            graph.advance(signal)
    with runtime(tmp_path / "graph.db", binding, session, gateway, lambda: 2000) as graph:
        second = graph.advance(signal)
        assert second.status == "WAITING"
        with pytest.raises(RuntimeContractError, match="STALE_RESUME"):
            graph.advance(signal)
        final = graph.advance(
            ResumeSignal(
                checkpoint_id=second.checkpoint_id,
                wait_id=second.wait.wait_id,
            )
        )
        assert final.status == "CANDIDATE_READY"
        assert gateway.count(binding) == 5


def test_rejection_is_terminal_not_a_model_repair(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db", accepted=False)
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        assert graph.advance().status == "REJECTED"
        assert graph.advance().status == "REJECTED"
        assert gateway.count(binding) == 5


def test_foreign_receipt_rejected(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    gateway.foreign_receipt = True
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        with pytest.raises(RuntimeContractError, match="RECEIPT_BINDING_MISMATCH"):
            graph.advance()


@pytest.mark.parametrize("after_io", [False, True])
def test_revocation_blocks_current_and_cached_work(tmp_path, binding, session, after_io):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    gateway.revoke_after_call = after_io
    gateway.revoked = not after_io
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        with pytest.raises(RuntimeContractError, match="EXECUTION_AUTHORITY_LOST"):
            graph.advance()
        assert gateway.count(binding) == (1 if after_io else 0)


def test_revoke_after_completion_still_blocks_cached_result(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        graph.advance()
        gateway.generation = 2
        with pytest.raises(RuntimeContractError, match="EXECUTION_AUTHORITY_LOST"):
            graph.advance()


@pytest.mark.parametrize("budget", ["max_model_calls", "max_tool_calls"])
def test_step_budget_is_bounded(tmp_path, binding, session, budget):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    gateway.loop = True
    limited = binding.model_copy(update={budget: 1})
    with runtime(tmp_path / "graph.db", limited, session, gateway) as graph:
        with pytest.raises(RuntimeContractError, match="STEP_BUDGET_EXHAUSTED"):
            graph.advance()
        assert gateway.count(binding) <= 3


def test_expired_and_wrong_artifact_fail_before_calls(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    with runtime(
        tmp_path / "graph.db", binding, session, gateway, lambda: binding.deadline_epoch_ms
    ) as graph:
        with pytest.raises(RuntimeContractError, match="RUN_DEADLINE_EXPIRED"):
            graph.advance()
    wrong = binding.model_copy(update={"graph_artifact_digest": "b" * 64})
    with pytest.raises(RuntimeContractError, match="GRAPH_ARTIFACT_MISMATCH"):
        with runtime(tmp_path / "graph.db", wrong, session, gateway):
            pass
    assert gateway.count(binding) == 0


def test_contracts_fail_closed(binding):
    with pytest.raises(ValidationError):
        ModelDecision()
    with pytest.raises(ValidationError):
        type(binding).model_validate({**binding.model_dump(), "api_key": "must-not-be-here"})
    with pytest.raises(ValidationError):
        type(binding).model_validate({**binding.model_dump(), "run_id": "20001"})


def test_inherited_cloud_tracing_is_disabled(tmp_path, binding, session):
    class LocalGateway(LedgerGateway):
        def invoke(self, *args):
            assert get_tracing_context()["enabled"] is False
            return super().invoke(*args)

    gateway = LocalGateway(tmp_path / "ledger.db")
    with tracing_context(enabled=True):
        with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
            assert graph.advance().status == "CANDIDATE_READY"


@pytest.mark.parametrize("kind", ["MODEL", "TOOL", "VALIDATE"])
def test_v2_cold_recovery_preserves_platform_tool_ids(tmp_path, binding, session, kind):
    binding = binding.model_copy(update={"state_schema_version": 2})
    ledger = LedgerGateway(tmp_path / "ledger.db")

    class DropOnce:
        dropped = False
        pending = None

        def authorize(self, binding, session):
            ledger.authorize(binding, session)

        def invoke(self, binding, session, request):
            result = ledger.invoke(binding, session, request)
            if request.kind == kind and not self.dropped:
                self.dropped = True
                self.pending = request
                raise InjectedCrash("committed response lost")
            return result

    gateway = DropOnce()
    path = tmp_path / "graph.db"
    with runtime(path, binding, session, gateway) as graph:
        with pytest.raises(InjectedCrash):
            graph.advance()
        pending = graph._request(graph.graph.get_state(graph.config).values)
        assert pending == gateway.pending
        if kind == "TOOL":
            assert pending.operation_id == pending.tool.arguments_ref
            assert pending.operation_id.startswith("intent:")
        if kind == "VALIDATE":
            assert pending.operation_id != "validate:1"
    with runtime(path, binding, session, gateway) as graph:
        result = graph.advance()
        assert result.status == "CANDIDATE_READY"
        assert ledger.count(binding) == 5
        assert graph.advance() == result
    # Same thread cannot be reopened using the old frozen binding version.
    old = binding.model_copy(update={"state_schema_version": 1})
    with runtime(path, old, session, gateway) as graph:
        with pytest.raises(RuntimeContractError, match="CHECKPOINT_BINDING_MISMATCH"):
            graph.advance()


@pytest.mark.parametrize("stop_at_authorize", [True, False])
def test_v2_persistent_stop_is_checkpointed(tmp_path, binding, session, stop_at_authorize):
    from workflow_agent_runtime.contracts import RuntimeStopped

    binding = binding.model_copy(update={"state_schema_version": 2})

    class StoppedGateway:
        calls = 0
        stopped = stop_at_authorize

        def authorize(self, binding, session):
            if self.stopped:
                raise RuntimeStopped("RUN_STOPPED")

        def invoke(self, binding, session, request):
            self.calls += 1
            self.stopped = True
            raise RuntimeStopped("RUN_STOPPED")

    gateway = StoppedGateway()
    for _ in range(2):
        with runtime(tmp_path / "stopped.db", binding, session, gateway) as graph:
            view = graph.advance()
            assert view.status == "STOPPED"
            assert view.candidate_ref is None
            assert view.validation_ref is None
            assert not graph.graph.get_state(graph.config).next
    assert gateway.calls == (0 if stop_at_authorize else 1)


@pytest.mark.parametrize("crash_at", ["trigger", "correction"])
def test_explicit_correction_survives_cold_checkpoint_recovery(
    tmp_path, binding, session, crash_at
):
    from workflow_agent_runtime.contracts import CorrectionRequired, fingerprint

    binding = binding.model_copy(update={"state_schema_version": 2})

    class CorrectionGateway(LedgerGateway):
        trigger = None
        crashed = False
        correction_ids = []

        def invoke(self, binding, session, request):
            if request.kind == "TOOL":
                self.trigger = request
                if crash_at == "trigger" and not self.crashed:
                    self.crashed = True
                    raise InjectedCrash("grant committed before redirect")
                raise CorrectionRequired("CORRECTION_REQUIRED")
            if request.operation_id.startswith("correction:"):
                assert request.operation_id == "correction:" + fingerprint(
                    [binding.digest, self.trigger.digest]
                )
                self.correction_ids.append(request.operation_id)
                result = super().invoke(binding, session, request)
                if crash_at == "correction" and not self.crashed:
                    self.crashed = True
                    raise InjectedCrash("correction receipt committed")
                return result
            return super().invoke(binding, session, request)

    gateway = CorrectionGateway(tmp_path / "ledger.db")
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        with pytest.raises(InjectedCrash):
            graph.advance()
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        assert graph.advance().status == "CANDIDATE_READY"
        snapshot = graph.graph.get_state(graph.config)
        assert snapshot.values["model_calls"] == 2
        assert len(snapshot.values["receipt_refs"]) == 3
    assert len(set(gateway.correction_ids)) == 1
    assert gateway.count(binding) == 3


@pytest.mark.parametrize("crash_phase", ["plan", "plan-validate", None])
def test_planning_nodes_recover_persisted_proposal_and_acceptance(
    tmp_path, binding, session, crash_phase
):
    import sqlite3

    from workflow_agent_runtime.contracts import InvocationReceipt, PlanRequired

    binding = binding.model_copy(update={"state_schema_version": 2})

    class PlanningGateway(LedgerGateway):
        crash_phase = None

        def invoke(self, binding, session, request):
            phase = request.operation_id.split(":", 1)[0]
            with sqlite3.connect(self.path) as connection:
                if phase not in ("plan", "plan-validate"):
                    if not connection.execute(
                        "SELECT 1 FROM receipts WHERE operation_id LIKE 'plan-validate:%'"
                    ).fetchone():
                        raise PlanRequired("PLAN_REQUIRED")
                    return super().invoke(binding, session, request)
                old = connection.execute(
                    "SELECT body FROM receipts WHERE operation_id=?", (request.operation_id,)
                ).fetchone()
                if old:
                    return InvocationReceipt.model_validate_json(old[0])
                result = InvocationReceipt(
                    binding_digest=binding.digest,
                    operation_id=request.operation_id,
                    request_digest=request.digest,
                    receipt_ref="receipt:" + request.operation_id,
                    decision=ModelDecision(candidate_ref="candidate:plan")
                    if phase == "plan"
                    else None,
                    accepted=True if phase == "plan-validate" else None,
                )
                connection.execute(
                    "INSERT INTO receipts VALUES (?, ?, ?, ?)",
                    (
                        binding.thread_id,
                        request.operation_id,
                        request.digest,
                        result.model_dump_json(),
                    ),
                )
            if phase == self.crash_phase:
                raise InjectedCrash("after durable plan phase, before checkpoint")
            return result

    gateway = PlanningGateway(tmp_path / "ledger.db")
    gateway.crash_phase = crash_phase
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        if crash_phase:
            with pytest.raises(InjectedCrash):
                graph.advance()
        else:
            assert graph.advance().status == "CANDIDATE_READY"
    gateway = PlanningGateway(tmp_path / "ledger.db")
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        assert graph.advance().status == "CANDIDATE_READY"
        state = graph.graph.get_state(graph.config).values
        assert state["model_calls"] == 2
        assert state["plan_receipt_ref"].startswith("receipt:plan-validate:")
        assert len(state["receipt_refs"]) == 3
    assert gateway.count(binding) == 4


@pytest.mark.parametrize("crash_phase", ["trigger", "replan", "accept", "wait"])
def test_revision_switch_survives_lost_redirect_proposal_and_acceptance(
    tmp_path, binding, session, crash_phase
):
    import sqlite3

    from workflow_agent_runtime.contracts import (
        Deferred,
        InvocationReceipt,
        PlanRequired,
        ReplanRequired,
        ToolCall,
    )

    binding = binding.model_copy(update={"state_schema_version": 2})

    class RevisionGateway(LedgerGateway):
        crashed = False

        def invoke(self, binding, session, request):
            phase = None
            with sqlite3.connect(self.path) as connection:
                old = connection.execute(
                    "SELECT body FROM receipts WHERE operation_id=?", (request.operation_id,)
                ).fetchone()
                if old:
                    return InvocationReceipt.model_validate_json(old[0])
                accepted = connection.execute(
                    "SELECT count(*) FROM receipts WHERE operation_id LIKE 'plan-validate:%'"
                ).fetchone()[0]
                if request.kind == "TOOL":
                    if crash_phase == "trigger" and not self.crashed:
                        self.crashed = True
                        raise InjectedCrash("durable grant before redirect")
                    raise ReplanRequired("REPLAN_REQUIRED")
                decision = None
                if request.kind == "MODEL":
                    if request.operation_id.startswith("plan:"):
                        decision = ModelDecision(candidate_ref="candidate:" + request.operation_id)
                        phase = (
                            "replan"
                            if request.operation_id.startswith("plan:correction:")
                            else None
                        )
                    elif accepted == 0:
                        raise PlanRequired("PLAN_REQUIRED")
                    elif accepted == 1:
                        decision = ModelDecision(
                            tools=(
                                ToolCall(
                                    tool_code="lookup",
                                    arguments_ref="intent:blocked",
                                    arguments_digest="a" * 64,
                                ),
                            )
                        )
                    else:
                        decision = ModelDecision(candidate_ref="candidate:final")
                elif accepted == 1 and request.operation_id.startswith("plan-validate:"):
                    phase = "accept"
                    if crash_phase == "wait" and not self.crashed:
                        self.crashed = True
                        return Deferred(
                            wait_id="plan-switch:test", reason="RETRY", not_before_epoch_ms=1000
                        )
                result = InvocationReceipt(
                    binding_digest=binding.digest,
                    operation_id=request.operation_id,
                    request_digest=request.digest,
                    receipt_ref="receipt:" + request.operation_id,
                    decision=decision,
                    accepted=True if request.kind == "VALIDATE" else None,
                )
                connection.execute(
                    "INSERT INTO receipts VALUES (?, ?, ?, ?)",
                    (
                        binding.thread_id,
                        request.operation_id,
                        request.digest,
                        result.model_dump_json(),
                    ),
                )
            if phase == crash_phase and not self.crashed:
                self.crashed = True
                raise InjectedCrash("persisted before checkpoint")
            return result

    gateway = RevisionGateway(tmp_path / "ledger.db")
    signal = None
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        if crash_phase == "wait":
            view = graph.advance()
            assert view.status == "WAITING"
            signal = ResumeSignal(checkpoint_id=view.checkpoint_id, wait_id=view.wait.wait_id)
        else:
            with pytest.raises(InjectedCrash):
                graph.advance()
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        assert graph.advance(signal).status == "CANDIDATE_READY"
        state = graph.graph.get_state(graph.config).values
        assert state["plan_revision"] == 2
        assert state["model_calls"] == 4
        assert state["correction_id"].startswith("plan:correction:")
    assert gateway.count(binding) == 7


@pytest.mark.parametrize("crash_phase", ["summary", "read", "second_summary", None])
def test_incremental_memory_and_local_read_survive_cold_recovery(
    tmp_path, binding, session, crash_phase
):
    import sqlite3

    from workflow_agent_runtime.contracts import ContextRequired, InvocationReceipt, ToolCall

    binding = binding.model_copy(update={"state_schema_version": 2})

    class MemoryGateway(LedgerGateway):
        crashed = False
        blocked_id = None
        resumed_id = None

        def invoke(self, binding, session, request):
            with sqlite3.connect(self.path) as connection:
                old = connection.execute(
                    "SELECT body FROM receipts WHERE operation_id=?", (request.operation_id,)
                ).fetchone()
                if old:
                    return InvocationReceipt.model_validate_json(old[0])
                rows = [
                    InvocationReceipt.model_validate_json(row[0])
                    for row in connection.execute("SELECT body FROM receipts")
                ]
                tools = sum(row.operation_id.startswith("intent:business") for row in rows)
                reads = sum(row.operation_id.startswith("read:") for row in rows)
                summaries = sum(row.operation_id.startswith("summary:") for row in rows)
                phase = None
                decision = None
                if request.kind == "MODEL":
                    if request.operation_id.startswith("summary:"):
                        phase = "summary" if summaries == 0 else "second_summary"
                        if summaries:
                            assert "memory:1" in request.input_refs
                        decision = ModelDecision(candidate_ref=f"memory:{summaries + 1}")
                    elif tools == 1 and "memory:1" not in request.input_refs:
                        self.blocked_id = request.operation_id
                        raise ContextRequired("CONTEXT_REQUIRED")
                    elif tools == 1 and reads == 0:
                        self.resumed_id = request.operation_id
                        decision = ModelDecision(
                            tools=(
                                ToolCall(
                                    tool_code="graph_read_result",
                                    arguments_ref="intent:read",
                                    arguments_digest="a" * 64,
                                ),
                            )
                        )
                    elif tools == 2:
                        assert "memory:2" in request.input_refs
                        assert "read-context:1" in request.input_refs
                        decision = ModelDecision(candidate_ref="candidate:final")
                    else:
                        decision = ModelDecision(
                            tools=(
                                ToolCall(
                                    tool_code="lookup",
                                    arguments_ref=f"intent:business{tools + 1}",
                                    arguments_digest="b" * 64,
                                ),
                            )
                        )
                elif request.kind == "READ_RESULT":
                    phase = "read"
                    decision = ModelDecision(candidate_ref="read-context:1")
                result = InvocationReceipt(
                    binding_digest=binding.digest,
                    operation_id=request.operation_id,
                    request_digest=request.digest,
                    receipt_ref="receipt:" + str(len(rows) + 1),
                    decision=decision,
                    accepted=True if request.kind == "VALIDATE" else None,
                )
                connection.execute(
                    "INSERT INTO receipts VALUES (?, ?, ?, ?)",
                    (
                        binding.thread_id,
                        request.operation_id,
                        request.digest,
                        result.model_dump_json(),
                    ),
                )
            if phase and phase == crash_phase and not self.crashed:
                self.crashed = True
                raise InjectedCrash("committed before checkpoint")
            return result

    gateway = MemoryGateway(tmp_path / "ledger.db")
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        if crash_phase:
            with pytest.raises(InjectedCrash):
                graph.advance()
        else:
            assert graph.advance().status == "CANDIDATE_READY"
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        assert graph.advance().status == "CANDIDATE_READY"
        state = graph.graph.get_state(graph.config).values
        assert state["memory_ref"] == "memory:2"
        assert state["model_calls"] == 6
        assert state["tool_calls"] == 2
        assert state["read_calls"] == 1
    assert gateway.blocked_id == gateway.resumed_id
    assert gateway.count(binding) == 10


@pytest.mark.parametrize("crash_phase", ["grant", "repair", "validate", None])
def test_format_repair_uses_stable_grant_and_new_candidate_after_cold_resume(
    tmp_path, binding, session, crash_phase
):
    import sqlite3

    from workflow_agent_runtime.contracts import (
        FormatRepairRequired,
        InvocationReceipt,
        fingerprint,
    )

    binding = binding.model_copy(update={"state_schema_version": 2})

    class FormatGateway(LedgerGateway):
        def __init__(self, path):
            super().__init__(path)
            self.crashed = False
            with sqlite3.connect(self.path) as conn:
                conn.execute("CREATE TABLE IF NOT EXISTS grants (operation TEXT PRIMARY KEY)")

        def invoke(self, binding, session, request):
            self.authorize(binding, session)
            phase = None
            with sqlite3.connect(self.path) as conn:
                if request.kind == "VALIDATE" and request.input_refs[0] == "candidate:bad":
                    operation = "repair:" + fingerprint([binding.digest, request.digest])
                    conn.execute("INSERT OR IGNORE INTO grants VALUES (?)", (operation,))
                    conn.commit()
                    if crash_phase == "grant" and not self.crashed:
                        self.crashed = True
                        raise InjectedCrash("grant committed")
                    raise FormatRepairRequired("FORMAT_REPAIR_REQUIRED")
                old = conn.execute(
                    "SELECT request_hash,body FROM receipts WHERE operation_id=?",
                    (request.operation_id,),
                ).fetchone()
                if old:
                    assert old[0] == request.digest
                    return InvocationReceipt.model_validate_json(old[1])
                repair = request.operation_id.startswith("repair:")
                if repair:
                    assert conn.execute("SELECT operation FROM grants").fetchall() == [
                        (request.operation_id,)
                    ]
                    phase = "repair"
                elif request.kind == "VALIDATE":
                    assert request.input_refs[0] == "candidate:good"
                    phase = "validate"
                receipt = InvocationReceipt(
                    binding_digest=binding.digest,
                    operation_id=request.operation_id,
                    request_digest=request.digest,
                    receipt_ref="receipt:" + request.operation_id,
                    decision=ModelDecision(
                        candidate_ref="candidate:good" if repair else "candidate:bad"
                    )
                    if request.kind == "MODEL"
                    else None,
                    accepted=True if request.kind == "VALIDATE" else None,
                )
                conn.execute(
                    "INSERT INTO receipts VALUES (?,?,?,?)",
                    (
                        binding.thread_id,
                        request.operation_id,
                        request.digest,
                        receipt.model_dump_json(),
                    ),
                )
            if phase == crash_phase and phase and not self.crashed:
                self.crashed = True
                raise InjectedCrash("receipt committed")
            return receipt

    gateway = FormatGateway(tmp_path / "ledger.db")
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        if crash_phase:
            with pytest.raises(InjectedCrash):
                graph.advance()
        else:
            assert graph.advance().status == "CANDIDATE_READY"
    with runtime(tmp_path / "graph.db", binding, session, gateway) as graph:
        assert graph.advance().status == "CANDIDATE_READY"
        state = graph.graph.get_state(graph.config).values
        assert state["model_calls"] == 2
        assert state["candidate_ref"] == "candidate:good"
        assert "prepare_format_repair" in graph.graph.nodes
    assert gateway.count(binding) == 3

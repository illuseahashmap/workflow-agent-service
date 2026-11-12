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

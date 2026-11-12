import threading
import time
from contextlib import contextmanager

import pytest
from fake_gateway import LedgerGateway
from langgraph.checkpoint.sqlite import SqliteSaver

from workflow_agent_runtime.contracts import ResumeSignal, SliceBudget
from workflow_agent_runtime.worker import BoundedGraphWorker, GraphClaim


class Dispatch:
    def __init__(self, claim):
        self.ready = claim
        self.calls = 0
        self.deferred = []
        self.completed = []
        self.connection_open = False
        self.renew_ok = True
        self.renewed = threading.Event()
        self.drop_defer = False

    def claim_next(self):
        self.calls += 1
        claim, self.ready = self.ready, None
        return claim

    def renew(self, claim):
        self.renewed.set()
        return self.renew_ok

    def defer(self, claim, view):
        assert not self.connection_open
        self.deferred.append(view)
        self.ready = claim.model_copy(
            update={
                "resume": ResumeSignal(checkpoint_id=view.checkpoint_id, wait_id=view.wait.wait_id)
            }
        )
        if self.drop_defer:
            self.drop_defer = False
            self.ready = claim  # simulate expired old delivery after a lost defer
            raise OSError("lost ACK")
        return True

    def acknowledge(self, claim, view):
        assert not self.connection_open
        self.completed.append(view)
        return True


def make_worker(tmp_path, binding, session, gateway, *, budget=1):
    dispatch = Dispatch(GraphClaim(command_id="command-one", binding=binding, session=session))

    @contextmanager
    def checkpoint(_binding):
        dispatch.connection_open = True
        try:
            with SqliteSaver.from_conn_string(str(tmp_path / "checkpoints.db")) as saver:
                yield saver
        finally:
            dispatch.connection_open = False

    worker = BoundedGraphWorker(
        slots=1,
        dispatch=dispatch,
        gateway=gateway,
        checkpoint=checkpoint,
        artifact_digest="a" * 64,
        slice_budget=SliceBudget(max_invocations=budget),
        clock_ms=lambda: 1000,
        renew_interval_seconds=0.001,
    )
    return worker, dispatch


def drive(worker, done):
    outcomes = []
    stop = time.monotonic() + 5
    while not done() and time.monotonic() < stop:
        outcomes.extend(worker.poll())
        time.sleep(0.002)
    assert done()
    return outcomes


def test_worker_releases_connection_before_handoff_and_resumes_slices(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    worker, dispatch = make_worker(tmp_path, binding, session, gateway)
    try:
        drive(worker, lambda: bool(dispatch.completed))
        assert len(dispatch.deferred) == 4
        assert gateway.count(binding) == 5
        assert dispatch.completed[0].status == "CANDIDATE_READY"
    finally:
        worker.close()


def test_worker_recovers_latest_wait_after_lost_defer_ack(tmp_path, binding, session):
    gateway = LedgerGateway(tmp_path / "ledger.db")
    worker, dispatch = make_worker(tmp_path, binding, session, gateway)
    dispatch.drop_defer = True
    try:
        outcomes = drive(worker, lambda: bool(dispatch.completed))
        assert "RECOVERY_REQUIRED" in outcomes
        assert gateway.count(binding) == 5
        assert len(dispatch.deferred) == 4
    finally:
        worker.close()


@pytest.mark.parametrize("batch", [False, True])
def test_inflight_rpc_keeps_slot_and_lost_lease_stops_subsequent_calls(
    tmp_path, binding, session, batch
):
    entered, release = threading.Event(), threading.Event()

    class SlowGateway(LedgerGateway):
        def invoke(self, binding, session, request):
            entered.set()
            assert release.wait(5)
            return super().invoke(binding, session, request)

    gateway = SlowGateway(tmp_path / "ledger.db")
    worker, dispatch = make_worker(tmp_path, binding, session, gateway, budget=16)
    dispatch.renew_ok = False
    if batch:

        def rejected_batch(claims):
            dispatch.renewed.set()
            return [False] * len(claims)

        dispatch.renew_batch = rejected_batch
    try:
        worker.poll()
        assert entered.wait(5)
        initial_claims = dispatch.calls
        drive(worker, lambda: dispatch.renewed.is_set())
        for _ in range(10):
            worker.poll()
        assert dispatch.calls == initial_claims  # no queued claims while HTTP is blocked
        assert len(worker.jobs) == 1
        release.set()
        drive(worker, lambda: all(f.done() for f in worker.jobs))
        assert gateway.count(binding) == 1
        assert not dispatch.completed and not dispatch.deferred
    finally:
        release.set()
        worker.close()

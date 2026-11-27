import json
import os
import subprocess
import sys

import psycopg
import pytest
from fake_gateway import LedgerGateway

from workflow_agent_runtime.contracts import RuntimeBusy, fingerprint
from workflow_agent_runtime.graph import GovernedAgentGraph
from workflow_agent_runtime.postgres import exclusive_postgres_checkpoint

pytestmark = pytest.mark.postgres


def test_exclusive_session_and_connection_loss(postgres_dsn, binding):
    with exclusive_postgres_checkpoint(postgres_dsn, binding.thread_id) as old:
        with pytest.raises(RuntimeBusy):
            with exclusive_postgres_checkpoint(postgres_dsn, binding.thread_id):
                pytest.fail("same thread acquired twice")
        with exclusive_postgres_checkpoint(postgres_dsn, fingerprint("another-thread")):
            pass  # no global serialization
        pid = old.conn.info.backend_pid
        with psycopg.connect(postgres_dsn, autocommit=True) as admin:
            assert admin.execute("SELECT pg_terminate_backend(%s, 5000)", (pid,)).fetchone()[0]
        with exclusive_postgres_checkpoint(postgres_dsn, binding.thread_id) as successor:
            assert successor.conn.info.backend_pid != pid
            with pytest.raises(psycopg.Error):
                old.put_writes(
                    {
                        "configurable": {
                            "thread_id": binding.thread_id,
                            "checkpoint_ns": "",
                            "checkpoint_id": "stale",
                        }
                    },
                    [("late", "must-not-persist")],
                    "old-worker",
                )
            assert (
                successor.conn.execute(
                    "SELECT count(*) AS n FROM checkpoint_writes WHERE thread_id = %s "
                    "AND task_id = 'old-worker'",
                    (binding.thread_id,),
                ).fetchone()["n"]
                == 0
            )


def test_wait_releases_postgres_ownership(postgres_dsn, tmp_path, binding, session):
    current = binding.model_copy(update={"run_id": 91000})
    gateway = LedgerGateway(tmp_path / "ledger.db")
    gateway.defer = 1
    with exclusive_postgres_checkpoint(postgres_dsn, current.thread_id) as saver:
        graph = GovernedAgentGraph(
            binding=current,
            session=session,
            gateway=gateway,
            checkpointer=saver,
            artifact_digest="a" * 64,
        )
        assert graph.advance().status == "WAITING"
    with exclusive_postgres_checkpoint(postgres_dsn, current.thread_id) as saver:
        assert saver.get_tuple({"configurable": {"thread_id": current.thread_id}}) is not None


WORKER = """
import os
from fake_gateway import LedgerGateway
from workflow_agent_runtime.contracts import RunBinding, ExecutionSession
from workflow_agent_runtime.graph import GovernedAgentGraph
from workflow_agent_runtime.postgres import exclusive_postgres_checkpoint
binding = RunBinding.model_validate_json(os.environ['TEST_BINDING'])
gateway = LedgerGateway(os.environ['TEST_LEDGER'],
    crash=os.environ.get('TEST_CRASH'), hard_exit=True)
with exclusive_postgres_checkpoint(os.environ['TEST_DSN'], binding.thread_id) as saver:
    result = GovernedAgentGraph(binding=binding,
        session=ExecutionSession(generation=1, claim_token='process-test'),
        gateway=gateway, checkpointer=saver, artifact_digest='a'*64).advance()
    print(result.model_dump_json())
"""


@pytest.mark.parametrize("crash", ["tool:1", "model:2", "validate:1", "none"])
def test_process_exit_and_recovery(postgres_dsn, tmp_path, binding, crash):
    current = binding.model_copy(
        update={
            "run_id": 92000 + ["tool:1", "model:2", "validate:1", "none"].index(crash),
        }
    )
    ledger = tmp_path / "ledger.db"
    env = {
        **os.environ,
        "TEST_DSN": postgres_dsn,
        "TEST_BINDING": current.model_dump_json(),
        "TEST_LEDGER": str(ledger),
        "TEST_CRASH": crash,
        "PYTHONPATH": "tests",
    }
    options = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}

    def run():
        return subprocess.run(
            [sys.executable, "-c", WORKER],
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
            **options,
        )

    first = run()
    assert first.returncode == (0 if crash == "none" else 91), first.stderr
    # A new OS process uses a fresh saver and gateway; no Python heap survives.
    env.pop("TEST_CRASH")
    second = run()
    assert second.returncode == 0, second.stderr
    result = json.loads(second.stdout)
    assert result["status"] == "CANDIDATE_READY"
    assert result["candidate_ref"] == f"candidate:{current.run_id}"
    assert LedgerGateway(ledger).count(current) == 5

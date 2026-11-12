import os
import socket
import subprocess
import tempfile
from pathlib import Path

import pytest

from workflow_agent_runtime.contracts import ExecutionSession, RunBinding
from workflow_agent_runtime.postgres import initialize_checkpoint_store


@pytest.fixture
def binding():
    return RunBinding(
        tenant_code="tenant-a",
        run_id=20001,
        agent_version_id=3,
        graph_artifact_digest="a" * 64,
        input_ref="input:purchase:20001",
        subject_ref="subject:reviewer",
        deadline_epoch_ms=4_000_000_000_000,
    )


@pytest.fixture
def session():
    return ExecutionSession(generation=1, claim_token="test-session")


@pytest.fixture(scope="session")
def postgres_dsn(tmp_path_factory):
    """Only an explicitly supplied isolated DB, or a new temporary local cluster."""
    dsn = os.getenv("WORKFLOW_LANGGRAPH_TEST_DATABASE_URL")
    if dsn:
        initialize_checkpoint_store(dsn)
        yield dsn
        return
    bin_dir = os.getenv("WORKFLOW_LANGGRAPH_TEST_PG_BIN")
    if not bin_dir:
        pytest.skip("set isolated WORKFLOW_LANGGRAPH_TEST_DATABASE_URL or TEST_PG_BIN")
    root = tmp_path_factory.mktemp("langgraph-postgres")
    data = root / "data"
    binary = Path(bin_dir)
    options = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}

    def run(name, *args):
        executable = binary / (name + (".exe" if os.name == "nt" else ""))
        # A Windows postgres child can inherit pg_ctl's pipe handles. Waiting for
        # PIPE EOF would then wait for the database lifetime, not pg_ctl completion.
        with tempfile.TemporaryFile() as output:
            return subprocess.run(
                [str(executable), *map(str, args)],
                check=True,
                stdout=output,
                stderr=subprocess.STDOUT,
                timeout=45,
                **options,
            )

    run("initdb", "-D", data, "-U", "postgres", "-A", "trust", "--encoding=UTF8", "--locale=C")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    try:
        run(
            "pg_ctl",
            "-D",
            data,
            "-l",
            root / "server.log",
            "-o",
            f"-h 127.0.0.1 -p {port}",
            "-w",
            "start",
        )
        dsn = f"postgresql://postgres@127.0.0.1:{port}/postgres"
        initialize_checkpoint_store(dsn)
        yield dsn
    finally:
        if (data / "postmaster.pid").exists():
            run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")

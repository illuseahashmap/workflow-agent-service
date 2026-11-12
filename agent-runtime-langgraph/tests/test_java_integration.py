import os
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

import pytest


@pytest.mark.java
@pytest.mark.postgres
def test_java_tool_gateway_postgres_and_python_http(postgres_dsn, tmp_path):
    root = Path(__file__).resolve().parents[2]
    database = urlsplit(postgres_dsn)
    assert database.scheme in ("postgres", "postgresql")
    assert not database.query
    env = {
        **os.environ,
        "WORKFLOW_LANGGRAPH_JDBC_URL": f"jdbc:postgresql://{database.hostname}:{database.port}{database.path}",
        "WORKFLOW_LANGGRAPH_JDBC_USER": database.username or "postgres",
        "WORKFLOW_LANGGRAPH_JDBC_PASSWORD": database.password or "",
        "WORKFLOW_LANGGRAPH_CHECKPOINT_DSN": postgres_dsn,
    }
    wrapper = (
        ["cmd.exe", "/d", "/c", str(root / "mvnw.cmd")]
        if os.name == "nt"
        else ["bash", str(root / "mvnw")]
    )
    options = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
    log = tmp_path / "java-gateway.log"
    with log.open("wb") as output:
        completed = subprocess.run(
            [
                *wrapper,
                "-pl",
                "agent-engine",
                "-am",
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dtest=GraphToolGatewayTest,GraphGatewayIntegrationTest,GraphPersistenceIntegrationTest,AgentToolRegistryTest,AgentArchitectureTest",
                "test",
            ],
            cwd=root,
            env=env,
            stdout=output,
            stderr=subprocess.STDOUT,
            timeout=360,
            **options,
        )
    assert completed.returncode == 0, log.read_text(encoding="utf-8", errors="replace")[-14000:]
    import xml.etree.ElementTree as etree

    for test in ("GraphGatewayIntegrationTest", "GraphPersistenceIntegrationTest"):
        report = (
            root
            / "agent-engine/target/surefire-reports"
            / f"TEST-io.github.illuseahashmap.agent.runtime.application.graph.{test}.xml"
        )
        result = etree.parse(report).getroot()
        assert int(result.attrib["tests"]) > 0
        assert (
            result.attrib["skipped"] == result.attrib["failures"] == result.attrib["errors"] == "0"
        )

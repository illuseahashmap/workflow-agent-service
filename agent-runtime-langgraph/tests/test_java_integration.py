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
    # A previous run must not contribute coverage to this acceptance run.
    for coverage in root.glob("*/target/jacoco.exec"):
        coverage.unlink()
    with log.open("wb") as output:
        completed = subprocess.run(
            [
                *wrapper,
                "-pl",
                "workflow-boot",
                "-am",
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dtest=GraphRouteTest,GraphToolGatewayTest,GraphLoopDetectorTest,GraphTaskContractTest,GraphPlanTest,GraphGatewayIntegrationTest,GraphPersistenceIntegrationTest,GraphPythonRecoveryIntegrationTest,AgentToolRegistryTest,AgentArchitectureTest,GraphCompletionFlowableIntegrationTest,GraphDualPurchaseFlowableIntegrationTest,AgentCompletionRecoveryServiceTest,AgentOutputMappingResolverTest,GraphHostConfigurationTest,GraphHostControllerTest,GraphCallRecoveryWorkerTest",
                "org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check@verify-java-conventions",
                "test",
                "org.jacoco:jacoco-maven-plugin:0.8.14:report",
                "org.jacoco:jacoco-maven-plugin:0.8.14:check@check-governance",
            ],
            cwd=root,
            env=env,
            stdout=output,
            stderr=subprocess.STDOUT,
            timeout=900,
            **options,
        )
    assert completed.returncode == 0, log.read_text(encoding="utf-8", errors="replace")[-14000:]
    import xml.etree.ElementTree as etree

    for test in (
        "GraphGatewayIntegrationTest",
        "GraphPersistenceIntegrationTest",
        "GraphPythonRecoveryIntegrationTest",
    ):
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
    report = (
        root
        / "workflow-boot/target/surefire-reports"
        / "TEST-io.github.illuseahashmap.workflow.agent.GraphCompletionFlowableIntegrationTest.xml"
    )
    result = etree.parse(report).getroot()
    assert int(result.attrib["tests"]) >= 4
    assert result.attrib["skipped"] == result.attrib["failures"] == result.attrib["errors"] == "0"

    coverage = etree.parse(root / "agent-engine/target/site/jacoco/jacoco.xml").getroot()
    required = {
        "GraphCognitionGateway",
        "JdbcGraphExecutionStore",
        "JdbcGraphReservationStore",
        "JdbcGraphCallCapacityStore",
        "JdbcGraphCompletionStore",
    }
    present = {item.attrib["name"].rsplit("/", 1)[-1] for item in coverage.findall(".//class")}
    assert required <= present, "Governance coverage report omitted required classes"

    report = (
        root
        / "workflow-boot/target/surefire-reports"
        / (
            "TEST-io.github.illuseahashmap.workflow.agent."
            "GraphDualPurchaseFlowableIntegrationTest.xml"
        )
    )
    result = etree.parse(report).getroot()
    assert int(result.attrib["tests"]) == 1
    assert result.attrib["skipped"] == result.attrib["failures"] == result.attrib["errors"] == "0"

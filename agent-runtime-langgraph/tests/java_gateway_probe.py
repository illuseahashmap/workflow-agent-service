"""Invoked by the Java/PostgreSQL integration test, never a production entry point."""

import os
from concurrent.futures import ThreadPoolExecutor

from workflow_agent_runtime.contracts import (
    ExecutionSession,
    InvocationRequest,
    RunBinding,
    ToolCall,
)
from workflow_agent_runtime.http_gateway import GatewayFailure, HttpGovernedGateway

gateway = HttpGovernedGateway(os.environ["GRAPH_FIXTURE_URL"], allow_loopback_http=True)
session = ExecutionSession(generation=1, claim_token="fixture:20001")
second_session = ExecutionSession(generation=1, claim_token="fixture:20002")
third_session = ExecutionSession(generation=1, claim_token="fixture:20003")


def binding(run_id):
    return RunBinding(
        tenant_code="tenant-a",
        run_id=run_id,
        agent_version_id=3,
        graph_artifact_digest="a" * 64,
        input_ref=f"input:purchase:{run_id}",
        subject_ref="subject:reviewer",
        deadline_epoch_ms=4_000_000_000_000,
    )


def request(run_id):
    return InvocationRequest(
        kind="TOOL",
        operation_id="tool:1",
        input_refs=(),
        tool=ToolCall(
            tool_code="lookup",
            arguments_ref=f"args:{run_id}:1",
            arguments_digest=os.environ["GRAPH_FIXTURE_ARGUMENTS_DIGEST"],
        ),
    )


def rejected(call, code):
    try:
        call()
        raise AssertionError("request unexpectedly accepted")
    except GatewayFailure as failure:
        assert str(failure) == code, str(failure)


first, second = binding(20001), binding(20002)
gateway.authorize(first, session)
rejected(lambda: gateway.invoke(first, session, request(20001)), "GATEWAY_UNAVAILABLE")
# The Java fixture dropped the ACK after tool success. Retry has a stable operation ID.
receipt = gateway.invoke(first, session, request(20001))
assert gateway.invoke(first, session, request(20001)) == receipt
rejected(lambda: gateway.authorize(second, session), "GATEWAY_AUTHORITY_DENIED")
gateway.authorize(second, second_session)
other = gateway.invoke(second, second_session, request(20002))
assert receipt.receipt_ref != other.receipt_ref
rejected(lambda: gateway.invoke(second, second_session, request(20001)), "GATEWAY_REFERENCE_DENIED")
rejected(
    lambda: gateway.authorize(
        first, ExecutionSession(generation=2, claim_token=session.claim_token)
    ),
    "GATEWAY_AUTHORITY_DENIED",
)
rejected(
    lambda: gateway.authorize(first, ExecutionSession(generation=1, claim_token="wrong")),
    "GATEWAY_AUTHORITY_DENIED",
)
rejected(
    lambda: gateway.invoke(
        first,
        session,
        InvocationRequest(
            kind="MODEL",
            operation_id="model:1",
            input_refs=(first.input_ref,),
        ),
    ),
    "GATEWAY_OPERATION_UNSUPPORTED",
)


def concurrent_call(_):
    try:
        return gateway.invoke(binding(20003), third_session, request(20003))
    except GatewayFailure as failure:
        assert str(failure) == "GATEWAY_TOOL_IN_PROGRESS"
        assert failure.retryable
        return None


with ThreadPoolExecutor(max_workers=6) as pool:
    results = list(pool.map(concurrent_call, range(6)))
final = gateway.invoke(binding(20003), third_session, request(20003))
assert all(item is None or item == final for item in results)
print("JAVA_GATEWAY_PROBE_OK: ack-loss, replay, isolation, authority, unsupported, concurrency")

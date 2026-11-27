"""Cross-language dual-purchase recovery fixture; never a production endpoint."""

import json
import os
import time
from functools import partial
from pathlib import Path
from urllib.request import Request, urlopen

import psycopg

from workflow_agent_runtime.contracts import (
    ExecutionSession,
    InvocationRequest,
    ResumeSignal,
    RunBinding,
    SliceBudget,
)
from workflow_agent_runtime.graph import GovernedAgentGraph
from workflow_agent_runtime.http_dispatch import HttpDispatchClient
from workflow_agent_runtime.http_gateway import GatewayFailure, HttpGovernedGateway
from workflow_agent_runtime.postgres import (
    exclusive_postgres_checkpoint,
    initialize_checkpoint_store,
)

url = os.environ["DUAL_URL"]
dsn = os.environ["WORKFLOW_LANGGRAPH_CHECKPOINT_DSN"]
dispatch = None
claims = []
if os.environ.get("DUAL_DISPATCH_TOKEN"):
    initialize_checkpoint_store(dsn)
    dispatch = HttpDispatchClient(
        url,
        worker_token=os.environ["DUAL_DISPATCH_TOKEN"],
        worker_id="dual-flowable",
        artifact_digest="a" * 64,
        allow_loopback_http=True,
    )
    claims = [dispatch.claim_next(), dispatch.claim_next()]
    assert all(claims) and dispatch.claim_next() is None
    bindings = [c.binding for c in claims]
    sessions = [c.session for c in claims]
    assert dispatch.renew_batch(claims) == [True, True]
else:
    items = json.loads(os.environ["DUAL_BINDINGS"])
    bindings = [RunBinding.model_validate(x["binding"]) for x in items]
    sessions = [ExecutionSession.model_validate(x["session"]) for x in items]
gateway = HttpGovernedGateway(url, allow_loopback_http=True)
views = {}
first_memory = set()
losses = [0, 0]
takeovers = [0, 0]
memories = {}
tool_requests = {}
foreign_checked = set()
late_writes = 0
cold_opens = 0


def rejected(call):
    try:
        call()
    except GatewayFailure as failure:
        assert str(failure) in {
            "GATEWAY_AUTHORITY_DENIED",
            "GATEWAY_INVALID_REQUEST",
            "GATEWAY_REFERENCE_DENIED",
        }, str(failure)
    else:
        raise AssertionError("Foreign or fenced request unexpectedly accepted")


def takeover(i, graph, saver):
    global late_writes
    before = graph.graph.get_state(graph.config)
    body = json.dumps({"run_id": bindings[i].run_id, "generation": sessions[i].generation}).encode()
    request = Request(
        url + "/fixture/takeover", data=body, headers={"Content-Type": "application/json"}
    )
    with urlopen(request, timeout=10) as response:
        payload = response.read()
    if dispatch:
        old_claim = claims[i]
        claims[i] = dispatch.claim_next()
        assert claims[i] and claims[i].binding == bindings[i]
        assert not dispatch.renew(old_claim)
        new_session = claims[i].session
    else:
        new_session = ExecutionSession.model_validate_json(payload)
    assert new_session.generation > sessions[i].generation
    rejected(lambda: graph.advance())
    after = graph.graph.get_state(graph.config)
    assert before.config == after.config and before.values == after.values
    pid = saver.conn.info.backend_pid
    with psycopg.connect(dsn, autocommit=True) as admin:
        assert admin.execute("SELECT pg_terminate_backend(%s,5000)", (pid,)).fetchone()[0]
    with exclusive_postgres_checkpoint(dsn, bindings[i].thread_id) as successor:
        try:
            saver.put_writes(before.config, [("late", "must-not-persist")], "dual-old-worker")
        except psycopg.Error:
            late_writes += 1
        else:
            raise AssertionError("Dead predecessor wrote a checkpoint")
        assert (
            successor.conn.execute(
                "SELECT count(*) AS n FROM checkpoint_writes "
                "WHERE thread_id=%s AND task_id='dual-old-worker'",
                (bindings[i].thread_id,),
            ).fetchone()["n"]
            == 0
        )
    sessions[i] = new_session
    takeovers[i] += 1


for _ in range(60):
    for i, binding in enumerate(bindings):
        if i in views:
            continue
        with exclusive_postgres_checkpoint(dsn, binding.thread_id) as saver:
            cold_opens += 1
            graph = GovernedAgentGraph(
                binding=binding,
                session=sessions[i],
                gateway=gateway,
                checkpointer=saver,
                artifact_digest=binding.graph_artifact_digest,
                slice_budget=SliceBudget(max_invocations=1),
            )
            snapshot = graph.graph.get_state(graph.config)
            if snapshot.values.get("pending", {}).get("kind") == "TOOL":
                tool_requests[i] = InvocationRequest.model_validate_json(
                    json.dumps(snapshot.values["pending"])
                )
            if snapshot.values.get("memory_ref"):
                memories[i] = snapshot.values["memory_ref"]
            if len(memories) == 2 and i not in foreign_checked:
                refs = (binding.input_ref, *snapshot.values["receipt_refs"], memories[1 - i])
                bad = InvocationRequest(kind="MODEL", operation_id="model:foreign", input_refs=refs)
                rejected(partial(gateway.invoke, binding, sessions[i], bad))
                rejected(partial(gateway.invoke, binding, sessions[i], tool_requests[1 - i]))
                foreign_checked.add(i)
            waiting = snapshot.values.get("waiting")
            resume = None
            if waiting:
                delay = (waiting["not_before_epoch_ms"] - time.time_ns() // 1_000_000) / 1000
                if delay > 0:
                    time.sleep(min(delay, 1))
                    continue
                resume = ResumeSignal(
                    checkpoint_id=snapshot.config["configurable"]["checkpoint_id"],
                    wait_id=waiting["wait_id"],
                )
            try:
                view = graph.advance(resume)
            except GatewayFailure as failure:
                assert failure.retryable, str(failure)
                losses[i] += 1
                assert losses[i] == 1
                takeover(i, graph, saver)
                continue
            snapshot = graph.graph.get_state(graph.config)
            if snapshot.values.get("memory_ref") and i not in first_memory:
                first_memory.add(i)
                takeover(i, graph, saver)
                continue
            if view.status != "WAITING":
                assert view.status == ("CANDIDATE_READY" if i == 0 else "REJECTED")
                if dispatch:
                    assert dispatch.acknowledge(claims[i], view)
                views[i] = view.model_dump(mode="json")
    if len(views) == 2:
        break
assert len(views) == 2 and takeovers == [2, 2] and losses == [1, 1]
assert late_writes == 4 and len(foreign_checked) == 2
result = {
    "views": [views[i] for i in range(2)],
    "takeovers": takeovers,
    "lost_summary_replies": losses,
    "rejected_late_writes": late_writes,
    "foreign_reference_checks": 4,
    "cold_opens": cold_opens,
    "authenticated_dispatch": dispatch is not None,
}
Path(os.environ["DUAL_RESULT"]).write_text(json.dumps(result), encoding="utf-8")
print("DUAL_PURCHASE_RECOVERY_OK")

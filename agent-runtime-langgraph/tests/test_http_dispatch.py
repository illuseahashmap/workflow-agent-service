import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from workflow_agent_runtime.contracts import Deferred, RuntimeView
from workflow_agent_runtime.http_dispatch import HttpDispatchClient
from workflow_agent_runtime.http_gateway import GatewayFailure

TOKEN = "test-worker-token-not-a-provider-key"


@pytest.fixture
def dispatch_server(binding):
    state = {"calls": [], "empty": False, "worker": "worker-one", "artifact": "a" * 64}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            state["calls"].append((self.path, dict(self.headers), payload))
            status = 200
            if self.headers.get("Authorization") != "Bearer " + TOKEN:
                status, result = 403, {"error_code": "AUTHORITY_DENIED"}
            elif self.path.endswith("/claim"):
                result = {
                    "claim": None
                    if state["empty"]
                    else {
                        "command_id": "cmd:1",
                        "binding": binding.model_copy(
                            update={"graph_artifact_digest": state["artifact"]}
                        ).model_dump(mode="json"),
                        "generation": 3,
                        "capability": "graph.private-capability",
                        "worker_id": state["worker"],
                        "lease_expires_epoch_ms": time.time_ns() // 1_000_000 + 90_000,
                        "checkpoint_id": None,
                        "wait_id": None,
                    }
                }
            elif self.path.endswith("/renew-batch"):
                result = {"accepted": [True] * len(payload["claims"])}
            else:
                result = {"accepted": True}
            encoded = json.dumps(result).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)


def client(server, token=TOKEN):
    return HttpDispatchClient(
        server[0],
        worker_token=token,
        worker_id="worker-one",
        artifact_digest="a" * 64,
        allow_loopback_http=True,
    )


def test_claim_renew_wait_and_acknowledge_protocol(dispatch_server):
    dispatch = client(dispatch_server)
    claim = dispatch.claim_next()
    assert claim.session.generation == 3
    assert "private-capability" not in repr(claim)
    assert dispatch.renew(claim)
    assert dispatch.renew_batch([claim]) == [True]
    wait = RuntimeView(
        thread_id=claim.binding.thread_id,
        checkpoint_id="cp:1",
        status="WAITING",
        wait=Deferred(wait_id="wait:1", reason="QUOTA", not_before_epoch_ms=4000),
    )
    assert dispatch.defer(claim, wait)
    done = RuntimeView(
        thread_id=claim.binding.thread_id,
        checkpoint_id="cp:2",
        status="CANDIDATE_READY",
        candidate_ref="candidate:1",
        validation_ref="validation:1",
    )
    assert dispatch.acknowledge(claim, done)
    calls = dispatch_server[1]["calls"]
    assert len(calls) == 5
    assert calls[-1][2]["observation"]["validation_receipt_ref"] == "validation:1"
    for _, headers, body in calls:
        assert headers["Authorization"] == "Bearer " + TOKEN
        assert headers["X-Graph-Worker"] == "worker-one"
        assert TOKEN not in json.dumps(body)
        assert "Cookie" not in headers and "X-Workflow-Token" not in headers
    dispatch_server[1]["empty"] = True
    assert dispatch.claim_next() is None


@pytest.mark.parametrize("field,value", [("worker", "another-worker"), ("artifact", "b" * 64)])
def test_foreign_delivery_is_rejected(dispatch_server, field, value):
    dispatch_server[1][field] = value
    with pytest.raises(GatewayFailure, match="DISPATCH_AUTHORITY_MISMATCH"):
        client(dispatch_server).claim_next()


def test_wrong_credential_is_not_retried_or_exposed(dispatch_server):
    with pytest.raises(GatewayFailure, match="AUTHORITY_DENIED") as failure:
        client(dispatch_server, token="x" * 32).claim_next()
    assert not failure.value.retryable
    assert "x" * 32 not in str(failure.value)
    assert len(dispatch_server[1]["calls"]) == 1


def test_cross_run_observation_never_leaves_worker(dispatch_server, binding):
    dispatch = client(dispatch_server)
    claim = dispatch.claim_next()
    view = RuntimeView(thread_id="wrong-run", checkpoint_id="cp:1", status="REJECTED")
    with pytest.raises(GatewayFailure, match="DISPATCH_OBSERVATION_MISMATCH"):
        dispatch.acknowledge(claim, view)
    assert len(dispatch_server[1]["calls"]) == 1

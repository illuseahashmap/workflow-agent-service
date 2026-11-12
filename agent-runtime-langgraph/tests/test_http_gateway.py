import json
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from workflow_agent_runtime.contracts import ExecutionSession, InvocationRequest, fingerprint
from workflow_agent_runtime.http_gateway import GatewayFailure, HttpGovernedGateway


@pytest.fixture
def endpoint():
    state = {
        "status": 200,
        "body": None,
        "headers": {},
        "requests": [],
        "slow": False,
        "slow_headers": False,
    }

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_POST(self):
            data = self.rfile.read(int(self.headers["Content-Length"]))
            state["requests"].append((dict(self.headers), data))
            if state["slow_headers"]:
                try:
                    for byte in b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n":
                        self.wfile.write(bytes([byte]))
                        self.wfile.flush()
                        time.sleep(0.03)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                self.close_connection = True
                return
            request = json.loads(data)
            default = {
                "binding_digest": fingerprint(request["binding"]),
                "generation": request["generation"],
            }
            payload = state["body"]
            if payload is None:
                payload = json.dumps(default).encode()
            self.send_response(state["status"])
            self.send_header("Content-Type", "application/json")
            for key, value in state["headers"].items():
                self.send_header(key, value)
            if "Content-Length" not in state["headers"]:
                self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            try:
                if state["slow"]:
                    for byte in payload:
                        self.wfile.write(bytes([byte]))
                        self.wfile.flush()
                        time.sleep(0.03)
                else:
                    self.wfile.write(payload)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                self.close_connection = True

    class Server(ThreadingHTTPServer):
        def handle_error(self, request, client_address):
            if not isinstance(sys.exception(), (BrokenPipeError, ConnectionResetError)):
                super().handle_error(request, client_address)

    server = Server(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
    worker.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", state
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


def client(endpoint, **kwargs):
    return HttpGovernedGateway(endpoint[0], allow_loopback_http=True, **kwargs)


def test_authorization_is_bound_and_capability_only_in_header(endpoint, binding, session):
    client(endpoint).authorize(binding, session)
    headers, body = endpoint[1]["requests"][0]
    assert headers["Authorization"] == "Bearer " + session.claim_token
    assert session.claim_token.encode() not in body


@pytest.mark.parametrize(
    "body",
    [
        b"null",
        b"{}",
        b'{"generation":1,"generation":2}',
        b'{"binding_digest":"' + b"f" * 64 + b'","generation":1}',
    ],
)
def test_malformed_or_foreign_authorization_is_rejected(endpoint, binding, session, body):
    endpoint[1]["body"] = body
    with pytest.raises(GatewayFailure):
        client(endpoint).authorize(binding, session)


@pytest.mark.parametrize(
    "status,code,retryable",
    [
        (403, "AUTHORITY_DENIED", False),
        (409, "TOOL_IN_PROGRESS", True),
        (409, "TOOL_OUTCOME_UNKNOWN", False),
        (503, "INTERNAL_FAILURE", True),
        (429, "secret-provider-message", True),
    ],
)
def test_typed_errors_are_redacted_without_implicit_retries(
    endpoint, binding, session, status, code, retryable
):
    endpoint[1].update(
        status=status, body=json.dumps({"error_code": code, "message": "secret"}).encode()
    )
    with pytest.raises(GatewayFailure) as caught:
        client(endpoint).authorize(binding, session)
    assert caught.value.retryable is retryable
    assert "secret" not in str(caught.value)
    assert len(endpoint[1]["requests"]) == 1


def test_redirect_is_not_followed(endpoint, binding, session):
    endpoint[1].update(status=302, headers={"Location": endpoint[0] + "/stolen"})
    with pytest.raises(GatewayFailure, match="REDIRECT_REJECTED"):
        client(endpoint).authorize(binding, session)
    assert len(endpoint[1]["requests"]) == 1


@pytest.mark.parametrize("declared", [True, False])
def test_response_size_is_bounded(endpoint, binding, session, declared):
    endpoint[1]["body"] = b"x" * (HttpGovernedGateway.MAX_BYTES + 1)
    if declared:
        endpoint[1]["headers"] = {"Content-Length": "999999999"}
    with pytest.raises(GatewayFailure, match="RESPONSE_TOO_LARGE"):
        client(endpoint).authorize(binding, session)


def test_slow_trickle_obeys_total_budget(endpoint, binding, session):
    endpoint[1]["slow"] = True
    start = time.monotonic()
    with pytest.raises(GatewayFailure, match="TIMEOUT"):
        client(endpoint, rpc_timeout=0.15).authorize(binding, session)
    assert time.monotonic() - start < 1


def test_slow_headers_cannot_reset_total_timeout(endpoint, binding, session):
    endpoint[1]["slow_headers"] = True
    start = time.monotonic()
    with pytest.raises(GatewayFailure, match="TIMEOUT"):
        client(endpoint, rpc_timeout=0.15).authorize(binding, session)
    assert time.monotonic() - start < 1


def test_deferred_response_is_operation_bound(endpoint, binding, session):
    request = InvocationRequest(
        kind="MODEL", operation_id="model:1", input_refs=(binding.input_ref,)
    )
    response = {
        "binding_digest": binding.digest,
        "operation_id": request.operation_id,
        "request_digest": request.digest,
        "receipt": None,
        "deferred": {"wait_id": "wait:1", "reason": "QUOTA", "not_before_epoch_ms": 0},
    }
    endpoint[1]["body"] = json.dumps(response).encode()
    assert client(endpoint).invoke(binding, session, request).wait_id == "wait:1"
    response["operation_id"] = "model:2"
    endpoint[1]["body"] = json.dumps(response).encode()
    with pytest.raises(GatewayFailure, match="RECEIPT_MISMATCH"):
        client(endpoint).invoke(binding, session, request)


@pytest.mark.parametrize(
    "url",
    [
        "http://example.com",
        "http://localhost",
        "https://user:pass@example.com",
        "https://example.com?token=x",
        "https://example.com/not-origin",
    ],
)
def test_destination_is_configuration_not_arbitrary_tool_url(url):
    with pytest.raises(ValueError):
        HttpGovernedGateway(url, allow_loopback_http=True)


def test_insecure_tls_context_is_rejected():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    with pytest.raises(ValueError):
        HttpGovernedGateway("https://example.com", tls_context=context)


def test_expired_run_never_sends_request(endpoint, binding, session):
    expired = binding.model_copy(update={"deadline_epoch_ms": 1})
    with pytest.raises(GatewayFailure, match="DEADLINE_EXPIRED"):
        client(endpoint).authorize(expired, session)
    assert not endpoint[1]["requests"]


def test_capability_newline_cannot_reach_headers(endpoint, binding):
    # Even a malformed in-process caller cannot inject an HTTP header.
    session = ExecutionSession.model_construct(generation=1, claim_token="a\r\nInjected: true")
    with pytest.raises(GatewayFailure, match="INVALID_CAPABILITY"):
        client(endpoint).authorize(binding, session)
    assert not endpoint[1]["requests"]

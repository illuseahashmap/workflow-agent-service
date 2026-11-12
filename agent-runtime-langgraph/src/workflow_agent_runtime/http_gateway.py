"""Bounded, no-redirect client for the platform-owned internal gateway protocol."""

import http.client
import io
import json
import re
import ssl
import time
from urllib.parse import urlsplit

from pydantic import ValidationError

from .contracts import (
    Contract,
    Deferred,
    Digest,
    ExecutionSession,
    InvocationReceipt,
    InvocationRequest,
    Reference,
    RunBinding,
    RuntimeContractError,
)


class GatewayFailure(RuntimeContractError):
    def __init__(self, code: str, *, retryable: bool = False):
        super().__init__(code)
        self.retryable = retryable


class _BudgetedSocketReader(io.RawIOBase):
    """Apply the remaining budget to EACH socket read, including header/chunk lines."""

    def __init__(self, transport, budget):
        self.transport, self.budget = transport, budget
        self.raw = transport.makefile("rb", buffering=0)

    def readable(self):
        return True

    def readinto(self, buffer):
        self.transport.settimeout(self.budget())
        count = self.raw.readinto(buffer)
        self.budget()
        return count

    def close(self):
        try:
            self.raw.close()
        finally:
            super().close()


class Authorized(Contract):
    binding_digest: Digest
    generation: int


class GatewayReply(Contract):
    binding_digest: Digest
    operation_id: Reference
    request_digest: Digest
    receipt: InvocationReceipt | None
    deferred: Deferred | None


class HttpGovernedGateway:
    PREFIX = "/internal/agent-runtime/v1"
    MAX_BYTES = 262144
    SAFE_CODES = frozenset(
        {
            "INVALID_REQUEST",
            "AUTHORITY_DENIED",
            "BINDING_MISMATCH",
            "REFERENCE_DENIED",
            "DEADLINE_EXPIRED",
            "OPERATION_UNSUPPORTED",
            "TOOL_REJECTED",
            "TOOL_IN_PROGRESS",
            "TOOL_OUTCOME_UNKNOWN",
            "TOOL_TRANSIENT",
            "MODEL_IN_PROGRESS",
            "MODEL_BUDGET_EXHAUSTED",
            "MODEL_TRANSIENT",
            "MODEL_REJECTED",
            "INTERNAL_FAILURE",
        }
    )

    def __init__(
        self,
        endpoint: str,
        *,
        rpc_timeout: float = 30,
        allow_loopback_http: bool = False,
        tls_context: ssl.SSLContext | None = None,
    ):
        url = urlsplit(endpoint)
        if (
            url.username
            or url.password
            or url.query
            or url.fragment
            or url.path not in ("", "/")
            or not url.hostname
            or url.scheme not in ("https", "http")
        ):
            raise ValueError("gateway must be a configured HTTPS origin")
        if url.scheme != "https" and not (
            allow_loopback_http and url.hostname in ("127.0.0.1", "::1")
        ):
            raise ValueError("HTTP is restricted to explicit loopback testing")
        if not 0 < rpc_timeout <= 120:
            raise ValueError("rpc_timeout must be within (0, 120] seconds")
        context = tls_context or ssl.create_default_context()
        if not context.check_hostname or context.verify_mode != ssl.CERT_REQUIRED:
            raise ValueError("gateway TLS verification cannot be disabled")
        self.host, self.port, self.secure = url.hostname, url.port, url.scheme == "https"
        self.context, self.timeout = context, rpc_timeout

    def authorize(self, binding: RunBinding, session: ExecutionSession) -> None:
        payload = self._post("authorize", binding, session)
        try:
            result = Authorized.model_validate(payload)
        except ValidationError:
            raise GatewayFailure("GATEWAY_PROTOCOL_ERROR") from None
        if result.binding_digest != binding.digest or result.generation != session.generation:
            raise GatewayFailure("GATEWAY_AUTHORITY_MISMATCH")

    def invoke(
        self, binding: RunBinding, session: ExecutionSession, request: InvocationRequest
    ) -> InvocationReceipt | Deferred:
        payload = self._post("invoke", binding, session, request)
        try:
            result = GatewayReply.model_validate_json(json.dumps(payload))
        except ValidationError:
            raise GatewayFailure("GATEWAY_PROTOCOL_ERROR") from None
        expected = (binding.digest, request.operation_id, request.digest)
        if (result.binding_digest, result.operation_id, result.request_digest) != expected:
            raise GatewayFailure("GATEWAY_RECEIPT_MISMATCH")
        if (result.receipt is None) == (result.deferred is None):
            raise GatewayFailure("GATEWAY_PROTOCOL_ERROR")
        if result.receipt is not None:
            receipt = result.receipt
            if (receipt.binding_digest, receipt.operation_id, receipt.request_digest) != expected:
                raise GatewayFailure("GATEWAY_RECEIPT_MISMATCH")
            return receipt
        return result.deferred

    def _post(self, action, binding, session, request=None):
        # The capability is never serialized into graph state, a URL, or error diagnostics.
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,256}", session.claim_token):
            raise GatewayFailure("GATEWAY_INVALID_CAPABILITY")
        remaining = min(self.timeout, binding.deadline_epoch_ms / 1000 - time.time())
        if remaining <= 0:
            raise GatewayFailure("GATEWAY_DEADLINE_EXPIRED")
        body = {"binding": binding.model_dump(mode="json"), "generation": session.generation}
        if request is not None:
            body["request"] = request.model_dump(mode="json")
        return self.exchange(
            self.PREFIX + "/" + action,
            body,
            {"Authorization": "Bearer " + session.claim_token},
            remaining,
        )

    def exchange(self, path, body, headers, remaining=None):
        """Shared bounded transport; callers supply fixed internal paths, never user URLs."""
        remaining = min(self.timeout, remaining if remaining is not None else self.timeout)
        deadline = time.monotonic() + remaining
        encoded = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        if len(encoded) > self.MAX_BYTES:
            raise GatewayFailure("GATEWAY_REQUEST_TOO_LARGE")

        class RetainedConnection(
            http.client.HTTPSConnection if self.secure else http.client.HTTPConnection
        ):
            # HTTP/1.0 and Connection: close otherwise close the socket object after
            # headers, before our deadline-aware body reader can set its timeout.
            retain_socket = False

            def close(self):
                if not self.retain_socket:
                    super().close()

        connection = (
            RetainedConnection(self.host, self.port, timeout=remaining, context=self.context)
            if self.secure
            else RetainedConnection(self.host, self.port, timeout=remaining)
        )

        def budget():
            seconds = deadline - time.monotonic()
            if seconds <= 0:
                raise GatewayFailure("GATEWAY_TIMEOUT", retryable=True)
            return seconds

        class BudgetedResponse(http.client.HTTPResponse):
            def __init__(self, transport, *args, **kwargs):
                super().__init__(transport, *args, **kwargs)
                self.fp.close()
                self.fp = io.BufferedReader(_BudgetedSocketReader(transport, budget))

        connection.response_class = BudgetedResponse

        try:
            connection.connect()
            transport = connection.sock
            transport.settimeout(budget())
            connection.request(
                "POST",
                path,
                encoded,
                headers={
                    **headers,
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                },
            )
            transport.settimeout(budget())
            connection.retain_socket = True
            with connection.getresponse() as response:
                # Never follow redirects or forward the capability to another host.
                if 300 <= response.status < 400:
                    raise GatewayFailure("GATEWAY_REDIRECT_REJECTED")
                if response.getheader("Content-Encoding", "identity") != "identity":
                    raise GatewayFailure("GATEWAY_PROTOCOL_ERROR")
                if (
                    response.getheader("Content-Type", "").split(";")[0].strip()
                    != "application/json"
                ):
                    raise GatewayFailure("GATEWAY_PROTOCOL_ERROR")
                length = response.getheader("Content-Length")
                if length is not None and (not length.isdecimal() or int(length) > self.MAX_BYTES):
                    raise GatewayFailure("GATEWAY_RESPONSE_TOO_LARGE")
                data = bytearray()
                while True:
                    transport.settimeout(budget())
                    chunk = response.read1(min(65536, self.MAX_BYTES + 1 - len(data)))
                    if not chunk:
                        break
                    data.extend(chunk)
                    if len(data) > self.MAX_BYTES:
                        raise GatewayFailure("GATEWAY_RESPONSE_TOO_LARGE")
                budget()
                if length is not None and len(data) != int(length):
                    raise GatewayFailure("GATEWAY_PROTOCOL_ERROR")
                try:
                    value = json.loads(data, object_pairs_hook=self._unique_object)
                except (ValueError, UnicodeError):
                    raise GatewayFailure("GATEWAY_PROTOCOL_ERROR") from None
                if not isinstance(value, dict):
                    raise GatewayFailure("GATEWAY_PROTOCOL_ERROR")
                if response.status != 200:
                    code = value.get("error_code")
                    safe = (
                        code if isinstance(code, str) and code in self.SAFE_CODES else "HTTP_ERROR"
                    )
                    raise GatewayFailure(
                        "GATEWAY_" + safe,
                        retryable=response.status in (429, 502, 503, 504)
                        or safe in ("TOOL_IN_PROGRESS", "MODEL_IN_PROGRESS"),
                    )
                return value
        except TimeoutError:
            raise GatewayFailure("GATEWAY_TIMEOUT", retryable=True) from None
        except (OSError, http.client.HTTPException):
            raise GatewayFailure("GATEWAY_UNAVAILABLE", retryable=True) from None
        finally:
            connection.retain_socket = False
            connection.close()

    @staticmethod
    def _unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

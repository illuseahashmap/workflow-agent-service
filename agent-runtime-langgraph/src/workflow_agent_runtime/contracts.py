"""Platform-owned identities and reference-only graph exchange contracts.

No provider credentials, tenant-selected URLs, model SDK objects, or raw business
documents belong in these contracts. Gateway implementations must validate the
references against the Java platform's authoritative receipts and current policy.
"""

import hashlib
import json
from typing import Annotated, Literal, Protocol, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Reference = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_./:-]{1,256}$")]
Digest = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]


def fingerprint(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class RunBinding(Contract):
    tenant_code: Reference
    run_id: int = Field(gt=0)
    agent_version_id: int = Field(gt=0)
    graph_artifact_digest: Digest
    state_schema_version: Literal[1] = 1
    input_ref: Reference
    subject_ref: Reference
    deadline_epoch_ms: int = Field(gt=0)
    max_model_calls: int = Field(default=10, ge=1, le=100)
    max_tool_calls: int = Field(default=20, ge=0, le=200)

    @property
    def digest(self) -> str:
        return fingerprint(self.model_dump(mode="json"))

    @property
    def thread_id(self) -> str:
        # Version/input changes must fail against the same thread, not silently fork it.
        return fingerprint(["workflow-agent", self.tenant_code, self.run_id])


class ExecutionSession(Contract):
    generation: int = Field(gt=0)
    claim_token: Reference = Field(repr=False)


class ToolCall(Contract):
    tool_code: Reference
    arguments_ref: Reference
    arguments_digest: Digest


class ModelDecision(Contract):
    tools: tuple[ToolCall, ...] = Field(default=(), max_length=16)
    candidate_ref: Reference | None = None

    @model_validator(mode="after")
    def one_action(self) -> Self:
        if bool(self.tools) == bool(self.candidate_ref):
            raise ValueError("exactly one of tools or candidate_ref is required")
        return self


class InvocationRequest(Contract):
    kind: Literal["MODEL", "TOOL", "VALIDATE"]
    operation_id: Reference
    input_refs: tuple[Reference, ...] = Field(max_length=512)
    tool: ToolCall | None = None

    @model_validator(mode="after")
    def valid_tool_kind(self) -> Self:
        if (self.kind == "TOOL") != (self.tool is not None):
            raise ValueError("only TOOL requests have a tool intent")
        return self

    @property
    def digest(self) -> str:
        return fingerprint(self.model_dump(mode="json"))


class InvocationReceipt(Contract):
    binding_digest: Digest
    operation_id: Reference
    request_digest: Digest
    receipt_ref: Reference
    decision: ModelDecision | None = None
    accepted: bool | None = None


class Deferred(Contract):
    wait_id: Reference
    reason: Literal["QUOTA", "RETRY"]
    not_before_epoch_ms: int = Field(ge=0)


class ResumeSignal(Contract):
    checkpoint_id: Reference
    wait_id: Reference


class RuntimeView(Contract):
    thread_id: Reference
    checkpoint_id: Reference
    status: Literal["WAITING", "CANDIDATE_READY", "REJECTED"]
    wait: Deferred | None = None
    candidate_ref: Reference | None = None
    validation_ref: Reference | None = None


class GovernedGateway(Protocol):
    """Authenticated platform integration port, not an implementation of authorization.

    authorize must reject revoked/expired/stale sessions, including cached reads.
    invoke must atomically claim stable operation_id, compare request hashes,
    reuse persisted receipts, and enforce global budgets/current permissions.
    JDBC authority/model/tool/validation adapters are integration-tested. The Java
    production host stays disabled until graph policy parity and deployment gates pass.
    """

    def authorize(self, binding: RunBinding, session: ExecutionSession) -> None: ...

    def invoke(
        self, binding: RunBinding, session: ExecutionSession, request: InvocationRequest
    ) -> InvocationReceipt | Deferred: ...


class RuntimeContractError(RuntimeError):
    """Stable, non-sensitive reason; do not include raw payloads in errors."""


class RuntimeBusy(RuntimeError):
    """Another database session owns this graph thread; defer instead of stealing."""

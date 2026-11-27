"""Authenticated whole-graph delivery. Worker credentials never enter graph state."""

import re

from pydantic import Field, ValidationError

from .contracts import Contract, Digest, ExecutionSession, Reference, ResumeSignal, RunBinding
from .http_gateway import GatewayFailure, HttpGovernedGateway
from .worker import GraphClaim


class Lease(Contract):
    command_id: Reference
    binding: RunBinding
    generation: int = Field(gt=0)
    capability: Reference = Field(repr=False)
    worker_id: Reference
    lease_expires_epoch_ms: int = Field(gt=0)
    checkpoint_id: Reference | None
    wait_id: Reference | None


class ClaimReply(Contract):
    claim: Lease | None


class Accepted(Contract):
    accepted: bool


class Renewed(Contract):
    accepted: list[bool] = Field(max_length=32)


class ClaimRequest(Contract):
    artifact_digest: Digest


class HttpDispatchClient:
    def __init__(self, endpoint, *, worker_token, worker_id, artifact_digest, **transport_options):
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{32,256}", worker_token):
            raise ValueError("invalid worker credential configuration")
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", worker_id):
            raise ValueError("invalid worker identifier")
        self.request = ClaimRequest(artifact_digest=artifact_digest)
        self.worker_id = worker_id
        self._headers = {"Authorization": "Bearer " + worker_token, "X-Graph-Worker": worker_id}
        self.transport = HttpGovernedGateway(endpoint, rpc_timeout=2, **transport_options)

    def _send(self, action, payload, schema):
        result = self.transport.exchange(
            "/internal/agent-dispatch/v1/" + action, payload, self._headers
        )
        try:
            return schema.model_validate(result)
        except ValidationError:
            raise GatewayFailure("DISPATCH_PROTOCOL_ERROR") from None

    def claim_next(self):
        lease = self._send("claim", self.request.model_dump(), ClaimReply).claim
        if lease is None:
            return None
        if (
            lease.worker_id != self.worker_id
            or lease.binding.graph_artifact_digest != self.request.artifact_digest
            or bool(lease.checkpoint_id) != bool(lease.wait_id)
        ):
            raise GatewayFailure("DISPATCH_AUTHORITY_MISMATCH")
        return GraphClaim(
            command_id=lease.command_id,
            binding=lease.binding,
            session=ExecutionSession(generation=lease.generation, claim_token=lease.capability),
            lease_expires_epoch_ms=lease.lease_expires_epoch_ms,
            resume=ResumeSignal(checkpoint_id=lease.checkpoint_id, wait_id=lease.wait_id)
            if lease.wait_id
            else None,
        )

    def _lease(self, claim):
        return Lease(
            command_id=claim.command_id,
            binding=claim.binding,
            generation=claim.session.generation,
            capability=claim.session.claim_token,
            worker_id=self.worker_id,
            lease_expires_epoch_ms=claim.lease_expires_epoch_ms,
            checkpoint_id=claim.resume.checkpoint_id if claim.resume else None,
            wait_id=claim.resume.wait_id if claim.resume else None,
        ).model_dump(mode="json")

    def renew(self, claim):
        return self._send("renew", {"claim": self._lease(claim)}, Accepted).accepted

    def renew_batch(self, claims):
        if not 1 <= len(claims) <= 32:
            raise ValueError("invalid renewal batch size")
        return self._send(
            "renew-batch", {"claims": [self._lease(c) for c in claims]}, Renewed
        ).accepted

    def defer(self, claim, view):
        if view.wait is None or view.thread_id != claim.binding.thread_id:
            raise GatewayFailure("DISPATCH_OBSERVATION_MISMATCH")
        return self._send(
            "defer",
            {
                "claim": self._lease(claim),
                "checkpoint_id": view.checkpoint_id,
                "wait_id": view.wait.wait_id,
                "not_before_epoch_ms": view.wait.not_before_epoch_ms,
            },
            Accepted,
        ).accepted

    def acknowledge(self, claim, view):
        if view.thread_id != claim.binding.thread_id or view.status == "WAITING":
            raise GatewayFailure("DISPATCH_OBSERVATION_MISMATCH")
        return self._send(
            "acknowledge",
            {
                "claim": self._lease(claim),
                "observation": {
                    "status": view.status,
                    "checkpoint_id": view.checkpoint_id,
                    "candidate_ref": view.candidate_ref,
                    "validation_receipt_ref": view.validation_ref,
                },
            },
            Accepted,
        ).accepted

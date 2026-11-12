"""Durable test double, NOT a production authorization/receipt implementation."""

import os
import sqlite3

from workflow_agent_runtime.contracts import (
    Deferred,
    InvocationReceipt,
    ModelDecision,
    RuntimeContractError,
    ToolCall,
    fingerprint,
)


class InjectedCrash(RuntimeError):
    pass


class LedgerGateway:
    def __init__(self, path, *, crash=None, hard_exit=False, accepted=True):
        self.path = str(path)
        self.crash = crash
        self.hard_exit = hard_exit
        self.accepted = accepted
        self.generation = 1
        self.revoked = False
        self.defer = 0
        self.not_before = 0
        self.foreign_receipt = False
        self.revoke_after_call = False
        self.loop = False
        with sqlite3.connect(self.path) as connection:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS receipts "
                "(thread_id TEXT, operation_id TEXT, request_hash TEXT, body TEXT, "
                "PRIMARY KEY(thread_id, operation_id))"
            )

    def authorize(self, binding, session):
        if self.revoked or session.generation != self.generation:
            raise RuntimeContractError("EXECUTION_AUTHORITY_LOST")

    def count(self, binding):
        with sqlite3.connect(self.path) as connection:
            return connection.execute(
                "SELECT COUNT(*) FROM receipts WHERE thread_id = ?", (binding.thread_id,)
            ).fetchone()[0]

    def invoke(self, binding, session, request):
        self.authorize(binding, session)
        if self.defer:
            self.defer -= 1
            return Deferred(
                wait_id=f"wait:{self.defer}", reason="QUOTA", not_before_epoch_ms=self.not_before
            )
        with sqlite3.connect(self.path) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT request_hash, body FROM receipts WHERE thread_id = ? AND operation_id = ?",
                (binding.thread_id, request.operation_id),
            ).fetchone()
            if row:
                if row[0] != request.digest:
                    raise RuntimeContractError("ARGUMENT_CONFLICT")
                return InvocationReceipt.model_validate_json(row[1])
            decision = None
            accepted = None
            if request.kind == "MODEL":
                if request.operation_id == "model:1" or self.loop:
                    decision = ModelDecision(
                        tools=tuple(
                            ToolCall(
                                tool_code=name,
                                arguments_ref=f"args:{binding.run_id}:{name}",
                                arguments_digest=fingerprint([binding.run_id, name]),
                            )
                            for name in ("policy_lookup", "order_lookup")
                        )
                    )
                else:
                    decision = ModelDecision(candidate_ref=f"candidate:{binding.run_id}")
            elif request.kind == "VALIDATE":
                accepted = self.accepted
            result = InvocationReceipt(
                binding_digest="f" * 64 if self.foreign_receipt else binding.digest,
                operation_id=request.operation_id,
                request_digest=request.digest,
                receipt_ref=f"receipt:{binding.run_id}:{request.operation_id}",
                decision=decision,
                accepted=accepted,
            )
            connection.execute(
                "INSERT INTO receipts VALUES (?, ?, ?, ?)",
                (binding.thread_id, request.operation_id, request.digest, result.model_dump_json()),
            )
        if self.revoke_after_call:
            self.revoked = True
        if request.operation_id == self.crash:
            if self.hard_exit:
                os._exit(91)  # Kill without finally/connection cleanup, after durable receipt.
            raise InjectedCrash("after receipt commit, before graph checkpoint")
        return result

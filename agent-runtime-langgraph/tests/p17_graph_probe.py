"""Isolated P-17 acceptance only; real graph and PostgreSQL checkpoints."""

import json
import os
import time
from pathlib import Path

from psycopg.conninfo import make_conninfo

from workflow_agent_runtime.contracts import ExecutionSession, ResumeSignal, RunBinding, SliceBudget
from workflow_agent_runtime.graph import GovernedAgentGraph
from workflow_agent_runtime.http_gateway import GatewayFailure, HttpGovernedGateway
from workflow_agent_runtime.postgres import (
    exclusive_postgres_checkpoint,
    initialize_checkpoint_store,
)

binding = RunBinding.model_validate_json(os.environ["P17_BINDING"])
session = ExecutionSession(
    generation=int(os.environ["P17_GENERATION"]), claim_token=os.environ["P17_CAPABILITY"]
)
dsn = make_conninfo(**json.loads(os.environ["P17_DATABASE"]))
initialize_checkpoint_store(dsn)
gateway = HttpGovernedGateway(os.environ["P17_URL"], allow_loopback_http=True)
failures = 0
result = {"error": "OBSERVATION_LIMIT"}
for cold_open in range(64):
    try:
        with exclusive_postgres_checkpoint(dsn, binding.thread_id) as saver:
            graph = GovernedAgentGraph(
                binding=binding,
                session=session,
                gateway=gateway,
                checkpointer=saver,
                artifact_digest=binding.graph_artifact_digest,
                slice_budget=SliceBudget(max_invocations=2),
            )
            snapshot = graph.graph.get_state(graph.config)
            waiting = snapshot.values.get("waiting") if snapshot.values else None
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
            view = graph.advance(resume)
            result = {"view": view.model_dump(mode="json"), "cold_opens": cold_open + 1}
            if view.status != "WAITING":
                break
    except GatewayFailure as failure:
        failures += 1
        result = {
            "error": str(failure),
            "retryable": failure.retryable,
            "failures": failures,
            "cold_opens": cold_open + 1,
        }
        if not failure.retryable or failures >= 2:
            break
Path(os.environ["P17_RESULT"]).write_text(json.dumps(result), encoding="utf-8")

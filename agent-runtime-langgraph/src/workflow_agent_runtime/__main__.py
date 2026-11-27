"""Opt-in authenticated Worker. Checkpoint schema must be migrated separately."""

import logging
import os
import signal
import threading
import uuid

from .http_dispatch import HttpDispatchClient
from .http_gateway import HttpGovernedGateway
from .postgres import exclusive_postgres_checkpoint
from .worker import BoundedGraphWorker


def main():
    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    endpoint = os.environ["GRAPH_GATEWAY_URL"]
    digest = os.environ["GRAPH_ARTIFACT_DIGEST"]
    dsn = os.environ["WORKFLOW_LANGGRAPH_CHECKPOINT_DSN"]
    loopback = os.environ.get("GRAPH_LOOPBACK_HTTP") == "true"
    dispatch = HttpDispatchClient(
        endpoint,
        worker_token=os.environ["GRAPH_WORKER_TOKEN"],
        worker_id=os.environ.get("GRAPH_WORKER_ID", str(uuid.uuid4())),
        artifact_digest=digest,
        allow_loopback_http=loopback,
    )
    worker = BoundedGraphWorker(
        slots=int(os.environ.get("GRAPH_WORKER_SLOTS", "4")),
        dispatch=dispatch,
        gateway=HttpGovernedGateway(endpoint, allow_loopback_http=loopback),
        checkpoint=lambda binding: exclusive_postgres_checkpoint(dsn, binding.thread_id),
        artifact_digest=digest,
    )
    try:
        while not stop.is_set():
            try:
                for outcome in worker.poll():
                    logging.info("graph slice outcome: %s", outcome)
            except Exception:
                # Do not log server payloads, credentials, DSNs or business context.
                logging.warning("graph dispatch unavailable; durable lease recovery retained")
            stop.wait(0.5)
    finally:
        worker.close()


if __name__ == "__main__":
    main()

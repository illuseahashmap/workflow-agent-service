"""Use the official saver on ONE non-reconnecting, exclusively owned DB session.

The session advisory lock and all checkpoint writes share a connection. Losing it
loses both write capability and ownership: an old worker cannot reconnect and
write after a successor. Gateway session fencing is still required for external
calls. This is not a replacement graph scheduler or a TTL lease on a checkpoint.
"""

import hashlib
from collections.abc import Iterator
from contextlib import contextmanager

import psycopg
from langgraph.checkpoint.postgres import PostgresSaver
from psycopg.rows import dict_row

from .contracts import RuntimeBusy


def _connect(dsn: str) -> psycopg.Connection:
    return psycopg.connect(
        dsn,
        autocommit=True,
        prepare_threshold=0,
        row_factory=dict_row,
        connect_timeout=5,
        options="-c statement_timeout=5000 -c lock_timeout=1000",
    )


def initialize_checkpoint_store(migration_dsn: str) -> None:
    """Explicit deployment migration, never called implicitly by a runtime worker."""
    with _connect(migration_dsn) as connection:
        PostgresSaver(connection).setup()


@contextmanager
def exclusive_postgres_checkpoint(dsn: str, thread_id: str) -> Iterator[PostgresSaver]:
    if len(thread_id) != 64 or any(c not in "0123456789abcdef" for c in thread_id):
        raise ValueError("thread_id must be the platform-scoped SHA-256 identifier")
    key = int.from_bytes(
        hashlib.sha256(("workflow-agent-graph:" + thread_id).encode()).digest()[:8],
        byteorder="big",
        signed=True,
    )
    with _connect(dsn) as connection:
        row = connection.execute("SELECT pg_try_advisory_lock(%s) AS acquired", (key,)).fetchone()
        if not row["acquired"]:
            raise RuntimeBusy("GRAPH_THREAD_BUSY")
        # No pool/reconnect: session death is fatal. Closing releases the lock even on error.
        yield PostgresSaver(connection)

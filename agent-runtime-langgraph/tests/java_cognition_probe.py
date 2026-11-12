"""Real graph/checkpoints -> HTTP -> Java JDBC authority/model/tool/validation receipts.

Only the test model adapter and pre-V65 platform tables are fixtures. No paid calls.
"""

import os

from workflow_agent_runtime.contracts import ExecutionSession, RunBinding
from workflow_agent_runtime.graph import GovernedAgentGraph
from workflow_agent_runtime.http_gateway import GatewayFailure, HttpGovernedGateway
from workflow_agent_runtime.postgres import exclusive_postgres_checkpoint

binding = RunBinding.model_validate_json(os.environ["GRAPH_COGNITION_BINDING"])
session = ExecutionSession(
    generation=int(os.environ["GRAPH_COGNITION_GENERATION"]),
    claim_token=os.environ["GRAPH_COGNITION_CAPABILITY"],
)
gateway = HttpGovernedGateway(os.environ["GRAPH_COGNITION_URL"], allow_loopback_http=True)
failures = 0
completed = None
for _ in range(4):
    with exclusive_postgres_checkpoint(
        os.environ["WORKFLOW_LANGGRAPH_CHECKPOINT_DSN"], binding.thread_id
    ) as saver:
        graph = GovernedAgentGraph(
            binding=binding,
            session=session,
            gateway=gateway,
            checkpointer=saver,
            artifact_digest=binding.graph_artifact_digest,
        )
        try:
            view = graph.advance()
        except GatewayFailure as error:
            assert error.retryable
            failures += 1
            continue
        assert view.status == "CANDIDATE_READY"
        assert view.candidate_ref and view.validation_ref
        if completed:
            assert view == completed
        completed = view
assert failures == 2 and completed
print("JAVA_COGNITION_GRAPH_OK")

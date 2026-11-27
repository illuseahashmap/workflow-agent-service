package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

/** Run lifetime limits. Reservations are conservative: unknown or unused calls are not refunded. */
public interface GraphRunBudgetPort {
    record Policy(int logicalCalls, int physicalCalls, int validationCalls) {
        public Policy {
            if (logicalCalls < 1
                    || logicalCalls > 300
                    || physicalCalls < 1
                    || physicalCalls > 300
                    || validationCalls < 1
                    || validationCalls > 100) {
                throw new IllegalArgumentException("Invalid Run budget");
            }
        }
    }

    void admit(AuthorizedRun run, Invocation request);

    String reserveCall(AuthorizedRun run, Invocation request);

    void settleCall(AuthorizedRun run, String callAttemptId, long inputTokens, long outputTokens);

    void stop(AuthorizedRun run, String reason);

    /** Legacy adapters cannot silently admit a v2 Run without a durable budget implementation. */
    static GraphRunBudgetPort from(GraphRunAuthorityPort authority) {
        if (authority instanceof GraphRunBudgetPort budget) {
            return budget;
        }
        return new GraphRunBudgetPort() {
            private void legacy(AuthorizedRun run) {
                if (run.binding().stateSchemaVersion() != 1) {
                    throw new GraphGatewayException(GraphGatewayException.Code.AUTHORITY_DENIED);
                }
            }

            public void admit(AuthorizedRun run, Invocation request) {
                legacy(run);
            }

            public String reserveCall(AuthorizedRun run, Invocation request) {
                legacy(run);
                return null;
            }

            public void settleCall(AuthorizedRun run, String id, long in, long out) {
                legacy(run);
            }

            public void stop(AuthorizedRun run, String reason) {
                legacy(run);
            }
        };
    }
}

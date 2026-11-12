package io.github.illuseahashmap.agent.runtime.application.port;

import java.util.function.Function;

/** Local deterministic acceptance runs inside the same transaction as Run and completion Outbox. */
public interface GraphCompletionPort {
    boolean completeNext(Function<Evidence, Outcome> validator, AgentRunEventPublisher publisher);

    record Evidence(GraphRunAuthorityPort.AuthorizedRun run, GraphCognitionPort.History history,
                    boolean accepted, String reasonCode) { }
    record Outcome(boolean accepted, String resultStatus, String reasonCode, String outputSnapshot) {
        public Outcome {
            var status = io.github.illuseahashmap.agent.runtime.domain.ResultStatus.valueOf(resultStatus);
            if (accepted != (status == io.github.illuseahashmap.agent.runtime.domain.ResultStatus.SUCCESS)
                    || accepted != (outputSnapshot != null) || reasonCode == null || !reasonCode.matches("[A-Z0-9_]{1,64}")) {
                throw new IllegalArgumentException("Invalid graph completion outcome");
            }
        }
    }
}

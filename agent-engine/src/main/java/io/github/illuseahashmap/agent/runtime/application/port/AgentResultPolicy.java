package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;

/** Applies business-level acceptance rules after structural Schema validation succeeds. */
@FunctionalInterface
public interface AgentResultPolicy {

    Decision evaluate(AgentDefinitionVersion version, ModelProviderResponse response);

    record Decision(ResultStatus status, boolean accepted, String reasonCode) {

        public static Decision accept() {
            return new Decision(ResultStatus.SUCCESS, true, "RESULT_ACCEPTED");
        }

        public static Decision rejected(ResultStatus status, String reasonCode) {
            return new Decision(status, false, reasonCode);
        }
    }
}

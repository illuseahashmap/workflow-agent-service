package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.port.AgentResultPolicy;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Baseline deterministic result policy for MODEL_ONLY execution. */
@Component
public class DefaultAgentResultPolicy implements AgentResultPolicy {

    @Override
    public Decision evaluate(AgentDefinitionVersion version, ModelProviderResponse response) {
        if (!StringUtils.hasText(response.content())) {
            return Decision.rejected(ResultStatus.EMPTY, "AGENT_RESULT_EMPTY");
        }
        String finishReason = response.finishReason();
        if ("content_filter".equalsIgnoreCase(finishReason)) {
            return Decision.rejected(ResultStatus.REJECTED, "AGENT_RESULT_CONTENT_FILTERED");
        }
        if ("length".equalsIgnoreCase(finishReason)) {
            return Decision.rejected(ResultStatus.PARTIAL, "AGENT_RESULT_INCOMPLETE");
        }
        return Decision.accept();
    }
}

package io.github.illuseahashmap.agent.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.impl.DefaultAgentResultPolicy;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.util.function.Consumer;

/** Shared final-answer gate. Validation never invokes a model or repairs a business rejection. */
public final class AgentCandidateValidator {
    private final DefaultAgentResultPolicy results;
    private final AgentOutputSchemaValidator schemas;
    private final ObjectMapper json;

    public AgentCandidateValidator(AgentOutputSchemaValidator schemas, ObjectMapper json) {
        this.schemas = schemas;
        this.json = json;
        this.results = new DefaultAgentResultPolicy(json);
    }

    public void validate(AgentDefinitionVersion version, ModelProviderResponse answer, Consumer<String> verifyEvidence) {
        var decision = results.evaluate(version, answer);
        if (!decision.accepted()) {
            throw new AgentExecutionException(new AgentFailure(decision.reasonCode(), AgentFailureCategory.RESULT_POLICY,
                    false, decision.status(), "Agent result rejected by policy"));
        }
        boolean command = answer.toolCall() != null;
        try {
            var root = json.readTree(answer.content());
            if (root != null) {
                String action = root.path("action").asText();
                command |= "TOOL_CALL".equals(action) || "READ_RESULT".equals(action);
            }
        } catch (java.io.IOException ignored) {
            // A plain-text result can be valid when no JSON output schema is configured.
        }
        if (command) {
            throw new AgentExecutionException(new AgentFailure("AGENT_FINAL_COMMAND_INVALID", AgentFailureCategory.RESULT_POLICY,
                    false, ResultStatus.REJECTED, "A final answer cannot request another operation"));
        }
        schemas.validateOutput(version.outputSchema(), answer.content());
        verifyEvidence.accept(answer.content());
    }
}

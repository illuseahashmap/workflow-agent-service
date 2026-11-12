package io.github.illuseahashmap.agent.runtime.application.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.runtime.application.AgentCandidateValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.TaskMemory;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.History;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;
import java.nio.charset.StandardCharsets;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

/** Shared by graph VALIDATE and business acceptance; never repairs or calls a model. */
public final class GraphCandidateGate {
    private final AgentOutputSchemaValidator schemas;
    private final ObjectMapper json;
    private final CheckpointContextPolicy budget;

    public GraphCandidateGate(AgentOutputSchemaValidator schemas, ObjectMapper json, CheckpointContextPolicy budget) {
        this.schemas = schemas;
        this.json = json;
        this.budget = budget;
    }

    public void validate(AuthorizedRun run, AgentDefinitionVersion version, History history) {
        JsonNode input = new GraphGatewayCodec().read(history.inputSnapshot().getBytes(StandardCharsets.UTF_8), JsonNode.class).path("input");
        require(!input.isMissingNode() && !input.isNull());
        new AgentCandidateValidator(schemas, json).validate(version, history.candidate(), output -> {
            var context = new TaskMemory.Context(run.binding().tenantCode(), run.binding().runId(), run.processInstanceId(),
                    version, input.isTextual() ? input.textValue() : input.toString());
            var memory = new TaskMemory(context, json, budget);
            for (var fact : history.tools()) {
                memory.observe(new TaskMemory.Receipt(fact.receiptRef(), fact.receiptRef(), fact.toolCode(),
                        fact.argumentsJson(), fact.outputDigest()), fact.output());
            }
            memory.verify(output);
        });
    }
}

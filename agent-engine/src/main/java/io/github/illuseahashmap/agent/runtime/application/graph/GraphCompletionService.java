package io.github.illuseahashmap.agent.runtime.application.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCompletionPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCompletionPort.Evidence;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCompletionPort.Outcome;
import java.util.Map;

/** Accepts durable graph results, not graph nodes. Infrastructure faults roll back for later polling. */
public final class GraphCompletionService {
    private final GraphCompletionPort store;
    private final AgentDefinitionVersionRepository versions;
    private final AgentToolRegistry tools;
    private final GraphCandidateGate gate;
    private final AgentRunEventPublisher publisher;
    private final ObjectMapper json;

    public GraphCompletionService(GraphCompletionPort store, AgentDefinitionVersionRepository versions, AgentToolRegistry tools,
            GraphCandidateGate gate, AgentRunEventPublisher publisher, ObjectMapper json) {
        this.store = store;
        this.versions = versions;
        this.tools = tools;
        this.gate = gate;
        this.publisher = publisher;
        this.json = json;
    }

    public boolean completeNext() { return store.completeNext(this::validate, publisher); }

    private Outcome validate(Evidence evidence) {
        var run = evidence.run();
        if (!evidence.accepted()) { return rejected(evidence.reasonCode()); }
        var version = versions.findByVersionId(run.binding().tenantCode(), run.binding().agentVersionId()).orElse(null);
        if (version == null || version.status() != AgentVersionStatus.PUBLISHED) { return rejected("GRAPH_VERSION_UNAVAILABLE"); }
        try {
            var access = new AgentTool.ResultAccessContext(run.binding().tenantCode(), run.binding().agentVersionId(),
                    run.principalId(), run.versionToolSetJson(), run.nodeToolSetJson());
            for (var fact : evidence.history().tools()) { tools.validateResultAccess(access, fact.toolCode(), fact.output()); }
            gate.validate(run, version, evidence.history());
        } catch (AgentExecutionException denied) {
            if (denied.failure().retryable()) { throw denied; }
            return new Outcome(false, denied.failure().resultStatus().name(), denied.failure().errorCode(), null);
        }
        var answer = evidence.history().candidate();
        try {
            return new Outcome(true, "SUCCESS", "ACCEPTED", json.writeValueAsString(Map.of(
                    "content", answer.content(), "model", answer.actualModel() == null ? "" : answer.actualModel(),
                    "finishReason", answer.finishReason() == null ? "" : answer.finishReason(),
                    "resultStatus", "SUCCESS", "resultReason", "ACCEPTED")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }
    private Outcome rejected(String reason) { return new Outcome(false, "REJECTED", reason, null); }
}

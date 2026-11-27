package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetrievalUseCase;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Read-only governed knowledge tool. It never bypasses the retrieval application port. */
@Component
public final class KnowledgeSearchAgentTool implements AgentTool {
    private static final String INPUT_SCHEMA = """
            {"type":"object","required":["query"],"properties":{
            "query":{"type":"string","minLength":1,"maxLength":2000},
            "knowledgeScopes":{"type":"array","items":{"type":"string"}},
            "maxResults":{"type":"integer","minimum":1,"maximum":20}}}
            """;

    private final ObjectProvider<KnowledgeRetrievalUseCase> useCaseProvider;
    private final ObjectMapper objectMapper;

    public KnowledgeSearchAgentTool(
            ObjectProvider<KnowledgeRetrievalUseCase> useCaseProvider,
            ObjectMapper objectMapper) {
        this.useCaseProvider = useCaseProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() { return "knowledge_search"; }

    @Override
    public String inputSchema() { return INPUT_SCHEMA; }

    @Override
    public void validateResultAccess(ResultAccessContext context, String output) {
        try {
            if (context.agentVersionId() == null) { throw new IllegalStateException("Published AgentVersion is required"); }
            var result = objectMapper.readTree(output);
            var references = new java.util.ArrayList<io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference>();
            for (var evidence : result.path("evidence")) {
                references.add(objectMapper.treeToValue(evidence.path("reference"),
                        io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference.class));
            }
            java.util.Objects.requireNonNull(useCaseProvider.getIfAvailable()).validateResultAccess(context.tenantCode(),
                    context.agentVersionId(), context.principalId(), result.path("traceId").asText(), references);
        } catch (RuntimeException | JsonProcessingException failure) {
            throw new AgentExecutionException(new AgentFailure("KNOWLEDGE_RESULT_ACCESS_REVOKED",
                    AgentFailureCategory.RESULT_POLICY, false, ResultStatus.REJECTED,
                    "历史知识证据已失效或无权访问，请重新发起经过授权的运行"), failure);
        }
    }

    @Override
    public Result execute(Request request) {
        KnowledgeRetrievalUseCase useCase = useCaseProvider.getIfAvailable();
        if (useCase == null) {
            throw new IllegalStateException("Knowledge retrieval is not configured");
        }
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        String query = String.valueOf(args.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("knowledge_search query is required");
        }
        List<String> scopes = args.get("knowledgeScopes") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList() : List.of();
        int maxResults = args.get("maxResults") instanceof Number number
                ? Math.max(1, Math.min(20, number.intValue())) : 10;
        RetrievalRequest retrievalRequest = new RetrievalRequest(
                query, scopes, List.of(), null, maxResults,
                RetrievalRequest.StrategyHint.AUTO, 0, List.of());
        RetrievalResult result;
        try {
            result = request.agentVersionId() == null
                    ? useCase.search(retrievalRequest)
                    : useCase.searchForAgent(retrievalRequest, request.tenantCode(),
                            request.agentVersionId(), request.principalId());
        } catch (EmbeddingModelException exception) {
            throw new AgentExecutionException(new AgentFailure(exception.errorCode(),
                    exception.retryable() ? AgentFailureCategory.PROVIDER_TRANSIENT
                            : AgentFailureCategory.PROVIDER_PERMANENT,
                    exception.retryable(), ResultStatus.FAILED,
                    exception.retryable() ? "知识向量服务暂时不可用，系统将按策略重试"
                            : "知识向量服务配置或响应不满足契约"), exception);
        }
        if (result.abstained() || result.status() != io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus.SUCCESS) {
            ResultStatus resultStatus = switch (result.status()) {
                case EMPTY -> ResultStatus.EMPTY;
                case PARTIAL -> ResultStatus.PARTIAL;
                case REJECTED -> ResultStatus.REJECTED;
                case FAILED -> ResultStatus.FAILED;
                case SUCCESS -> ResultStatus.REJECTED;
            };
            throw new AgentExecutionException(new AgentFailure(
                    "KNOWLEDGE_EVIDENCE_INSUFFICIENT", AgentFailureCategory.RESULT_POLICY,
                    false, resultStatus, "Knowledge search did not provide sufficient authorized evidence"));
        }
        try {
            String output = objectMapper.writeValueAsString(Map.of(
                    "status", result.status().name(),
                    "traceId", result.retrievalTraceId(),
                    "strategy", result.strategy(),
                    "citations", result.citations().stream().map(c -> Map.of(
                            "citationId", c.citationId(), "reference", c.reference())).toList(),
                    "evidence", result.evidence(),
                    "warnings", result.warnings(),
                    "abstained", result.abstained()));
            // Never truncate a qualifier or claim complete citations for omitted text.
            if (output.length() > MAX_OUTPUT_CHARS) {
                throw new AgentExecutionException(new AgentFailure("KNOWLEDGE_EVIDENCE_BUDGET_EXCEEDED",
                        AgentFailureCategory.RESULT_POLICY, false, ResultStatus.PARTIAL,
                        "完整证据超出工具输出预算，请缩小检索范围后重试"));
            }
            return new Result(output, request.idempotencyKey());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Knowledge retrieval result cannot be serialized", exception);
        }
    }
}

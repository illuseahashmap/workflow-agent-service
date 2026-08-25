package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Input contract for retrieval; tenant identity is supplied by trusted context. */
public record RetrievalRequest(
        String query,
        List<String> knowledgeScopes,
        List<MetadataFilter> filters,
        Instant asOfTime,
        int maxResults,
        StrategyHint strategyHint,
        int maxHops,
        List<EvidenceType> requiredEvidenceTypes
) {

    public RetrievalRequest {
        query = Objects.requireNonNull(query, "query must not be null").trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        knowledgeScopes = List.copyOf(Objects.requireNonNullElse(knowledgeScopes, List.of()));
        filters = List.copyOf(Objects.requireNonNullElse(filters, List.of()));
        requiredEvidenceTypes = List.copyOf(
                Objects.requireNonNullElse(requiredEvidenceTypes, List.of()));
        if (maxResults < 1 || maxResults > 50) {
            throw new IllegalArgumentException("maxResults must be between 1 and 50");
        }
        if (maxHops < 0 || maxHops > 1) {
            throw new IllegalArgumentException("maxHops must be between 0 and 1 for the first slice");
        }
        strategyHint = Objects.requireNonNullElse(strategyHint, StrategyHint.AUTO);
    }

    public enum StrategyHint {
        AUTO,
        KEYWORD,
        VECTOR,
        HYBRID,
        GRAPH
    }

    public record MetadataFilter(String name, String value) {
        public MetadataFilter {
            name = Objects.requireNonNull(name, "filter name must not be null").trim();
            value = Objects.requireNonNull(value, "filter value must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("filter name must not be blank");
            }
        }
    }
}

package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.EvidenceVisibilityPort;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Rechecks current visibility even when the result comes from a durable tool ledger. */
@Component
public final class PostgresEvidenceVisibilityAdapter implements EvidenceVisibilityPort {
    private final NamedParameterJdbcTemplate jdbc;

    public PostgresEvidenceVisibilityAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean visible(String tenant, List<String> scopes, List<EvidenceReference> references, List<Long> indexes) {
        if (scopes.isEmpty() || indexes.isEmpty() || references.isEmpty() || references.size() > 100) {
            return false;
        }
        if (references.stream().anyMatch(reference -> reference.sourceType() != EvidenceType.CHUNK)) { return false; }
        var visible = jdbc.query("""
                SELECT d.external_document_id,d.version,c.chunk_id FROM knowledge_chunk c
                JOIN knowledge_document_version d ON d.id=c.document_version_id AND d.tenant_code=c.tenant_code
                JOIN knowledge_index_version i ON i.id=c.index_version_id AND i.tenant_code=c.tenant_code
                JOIN knowledge_source s ON s.tenant_code=c.tenant_code AND s.source_code=c.source_code AND s.enabled=TRUE
                WHERE c.tenant_code=:tenant AND c.source_code IN (:scopes) AND c.index_version_id IN (:indexes)
                  AND c.chunk_id IN (:locators) AND d.status IN ('READY','ACTIVE') AND i.status IN ('ACTIVE','RETIRED')
                """, Map.of("tenant", tenant, "scopes", scopes, "indexes", indexes,
                "locators", references.stream().map(EvidenceReference::locator).toList()),
                (row, n) -> new EvidenceReference(EvidenceType.CHUNK, row.getString(1), row.getString(2), row.getString(3)));
        return visible.size() == references.size() && new java.util.HashSet<>(visible).equals(new java.util.HashSet<>(references));
    }
}

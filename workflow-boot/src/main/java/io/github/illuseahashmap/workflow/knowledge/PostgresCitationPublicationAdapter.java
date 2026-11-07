package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.agent.definition.application.port.CitationPublicationPort;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public record PostgresCitationPublicationAdapter(NamedParameterJdbcTemplate jdbc) implements CitationPublicationPort {
    @Override
    public boolean containsAll(String tenant, long profile, Set<String> chunks) {
        if (chunks.isEmpty()) return false;
        int matched = jdbc.queryForObject("""
                SELECT count(DISTINCT c.chunk_id)
                FROM knowledge_retrieval_profile_version p
                JOIN knowledge_profile_index_binding b ON b.tenant_code=p.tenant_code AND b.profile_version_id=p.id
                JOIN knowledge_retrieval_profile_scope ps ON ps.tenant_code=b.tenant_code
                    AND ps.profile_version_id=b.profile_version_id AND ps.scope_code=b.source_code
                JOIN knowledge_source s ON s.tenant_code=b.tenant_code AND s.source_code=b.source_code AND s.enabled
                JOIN knowledge_index_version i ON i.tenant_code=b.tenant_code AND i.source_code=b.source_code
                    AND i.id=b.index_version_id AND i.status IN ('ACTIVE','RETIRED')
                JOIN knowledge_chunk c ON c.tenant_code=i.tenant_code AND c.index_version_id=i.id AND c.source_code=b.source_code
                JOIN knowledge_document_version d ON d.tenant_code=c.tenant_code AND d.id=c.document_version_id
                    AND d.source_code=c.source_code AND d.status IN ('ACTIVE','READY')
                WHERE p.tenant_code=:tenant AND p.id=:profile AND p.status='PUBLISHED' AND c.chunk_id IN (:chunks)
                """, Map.of("tenant", tenant, "profile", profile, "chunks", chunks), Integer.class);
        return matched == chunks.size();
    }
}

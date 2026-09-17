package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.AgentRetrievalProfileBinding;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public final class PostgresAgentRetrievalProfileBinding implements AgentRetrievalProfileBinding {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresAgentRetrievalProfileBinding(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Long> findProfileVersionId(String tenantCode, long agentVersionId) {
        return jdbcTemplate.query("""
                SELECT retrieval_profile_version_id
                FROM agent_definition_version
                WHERE tenant_code = :tenantCode AND id = :agentVersionId
                  AND status = 'PUBLISHED'
                """, Map.of("tenantCode", tenantCode, "agentVersionId", agentVersionId),
                (rs, rowNum) -> (Long) rs.getObject("retrieval_profile_version_id"))
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }
}

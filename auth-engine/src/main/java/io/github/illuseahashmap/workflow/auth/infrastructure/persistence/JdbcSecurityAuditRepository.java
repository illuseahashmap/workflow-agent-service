package io.github.illuseahashmap.workflow.auth.infrastructure.persistence;

import io.github.illuseahashmap.workflow.auth.domain.SecurityAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSecurityAuditRepository implements SecurityAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSecurityAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(String eventType,
                       String actorId,
                       String tenantCode,
                       String sourceAddress,
                       String subject,
                       String outcome,
                       String details) {
        jdbcTemplate.update("""
                INSERT INTO platform_security_audit
                    (event_type, actor_id, tenant_code, source_address, subject,
                     outcome, details, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, eventType, actorId, tenantCode, sourceAddress, subject, outcome, details);
    }
}

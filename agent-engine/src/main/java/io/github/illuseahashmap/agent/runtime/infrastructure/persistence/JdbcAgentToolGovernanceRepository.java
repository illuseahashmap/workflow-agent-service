package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.port.AgentToolDefinition;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolExecutionAuditRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolPolicyRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentToolGovernanceRepository
        implements AgentToolPolicyRepository, AgentToolExecutionAuditRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentToolGovernanceRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AgentToolDefinition> findAuthorized(String tenantCode, String toolCode) {
        return jdbcTemplate.query("""
                        SELECT d.tool_code, d.tool_name, d.input_schema, d.read_only
                        FROM agent_tool_definition d
                        JOIN agent_tool_tenant_grant g ON g.tool_code = d.tool_code
                        WHERE d.tool_code = :toolCode
                          AND d.enabled = TRUE
                          AND g.tenant_code = :tenantCode
                          AND g.enabled = TRUE
                        """,
                Map.of("tenantCode", tenantCode, "toolCode", toolCode),
                (resultSet, rowNum) -> new AgentToolDefinition(
                        resultSet.getString("tool_code"),
                        resultSet.getString("tool_name"),
                        resultSet.getString("input_schema"),
                        resultSet.getBoolean("read_only")))
                .stream().findFirst();
    }

    @Override
    public Optional<Audit> findByIdempotencyKey(String tenantCode, String toolCode, String idempotencyKey) {
        return jdbcTemplate.query("""
                        SELECT tenant_code, tool_code, idempotency_key, arguments_hash,
                               status, output_snapshot, error_code, trace_id, created_at
                        FROM agent_tool_execution_audit
                        WHERE tenant_code = :tenantCode
                          AND tool_code = :toolCode
                          AND idempotency_key = :idempotencyKey
                        """,
                Map.of("tenantCode", tenantCode, "toolCode", toolCode, "idempotencyKey", idempotencyKey),
                (resultSet, rowNum) -> mapAudit(resultSet)).stream().findFirst();
    }

    @Override
    public Claim tryClaim(Audit reservation) {
        var claimed = jdbcTemplate.query("""
                        INSERT INTO agent_tool_execution_audit (
                            tenant_code, tool_code, idempotency_key, arguments_hash,
                            status, output_snapshot, error_code, trace_id, created_at
                        ) VALUES (
                            :tenantCode, :toolCode, :idempotencyKey, :argumentsHash,
                            'RUNNING', NULL, NULL, :traceId, :createdAt
                        )
                        ON CONFLICT (tenant_code, tool_code, idempotency_key) DO NOTHING
                        RETURNING tenant_code, tool_code, idempotency_key, arguments_hash,
                                  status, output_snapshot, error_code, trace_id, created_at
                        """, auditParameters(reservation), (resultSet, rowNum) -> mapAudit(resultSet));
        if (!claimed.isEmpty()) {
            return new Claim(true, claimed.getFirst());
        }
        return new Claim(false, findByIdempotencyKey(
                reservation.tenantCode(), reservation.toolCode(), reservation.idempotencyKey()).orElseThrow());
    }

    @Override
    public Claim tryClaim(Audit reservation, String claimOwner, java.time.Instant leaseExpiresAt, java.time.Instant now) {
        var parameters = auditParameters(reservation);
        parameters.put("claimOwner", claimOwner);
        parameters.put("leaseExpiresAt", Timestamp.from(leaseExpiresAt));
        parameters.put("now", Timestamp.from(now));
        var claimed = jdbcTemplate.query("""
                        INSERT INTO agent_tool_execution_audit (
                            tenant_code, tool_code, idempotency_key, arguments_hash,
                            status, output_snapshot, error_code, trace_id, created_at,
                            claim_owner, lease_expires_at, fencing_token
                        ) VALUES (
                            :tenantCode, :toolCode, :idempotencyKey, :argumentsHash,
                            'RUNNING', NULL, NULL, :traceId, :createdAt,
                            :claimOwner, :leaseExpiresAt, 1
                        )
                        ON CONFLICT (tenant_code, tool_code, idempotency_key) DO UPDATE
                        SET status = 'RUNNING', output_snapshot = NULL, error_code = NULL,
                            trace_id = :traceId, claim_owner = :claimOwner,
                            lease_expires_at = :leaseExpiresAt,
                            fencing_token = agent_tool_execution_audit.fencing_token + 1
                        WHERE (
                            (agent_tool_execution_audit.status = 'FAILED'
                             AND agent_tool_execution_audit.arguments_hash = :argumentsHash)
                            OR (agent_tool_execution_audit.status = 'RUNNING'
                                AND agent_tool_execution_audit.arguments_hash = :argumentsHash
                                AND agent_tool_execution_audit.lease_expires_at <= :now)
                        )
                        RETURNING tenant_code, tool_code, idempotency_key, arguments_hash,
                                  status, output_snapshot, error_code, trace_id, created_at
                        """, parameters, (resultSet, rowNum) -> mapAudit(resultSet));
        if (!claimed.isEmpty()) {
            return new Claim(true, claimed.getFirst());
        }
        return new Claim(false, findByIdempotencyKey(
                reservation.tenantCode(), reservation.toolCode(), reservation.idempotencyKey()).orElseThrow());
    }

    @Override
    public void save(Audit audit) {
        var parameters = auditParameters(audit);
        jdbcTemplate.update("""
                        INSERT INTO agent_tool_execution_audit (
                            tenant_code, tool_code, idempotency_key, arguments_hash,
                            status, output_snapshot, error_code, trace_id, created_at
                        ) VALUES (
                            :tenantCode, :toolCode, :idempotencyKey, :argumentsHash,
                            :status, :outputSnapshot, :errorCode, :traceId, :createdAt
                        )
                        ON CONFLICT (tenant_code, tool_code, idempotency_key) DO NOTHING
                        """, parameters);
    }

    @Override
    public void complete(Audit audit) {
        jdbcTemplate.update("""
                        UPDATE agent_tool_execution_audit
                        SET status = :status, output_snapshot = :outputSnapshot,
                            error_code = :errorCode, trace_id = :traceId
                        WHERE tenant_code = :tenantCode
                          AND tool_code = :toolCode
                          AND idempotency_key = :idempotencyKey
                          AND status = 'RUNNING'
                        """, auditParameters(audit));
    }

    @Override
    public void complete(Audit audit, String claimOwner) {
        var parameters = auditParameters(audit);
        parameters.put("claimOwner", claimOwner);
        jdbcTemplate.update("""
                        UPDATE agent_tool_execution_audit
                        SET status = :status, output_snapshot = :outputSnapshot,
                            error_code = :errorCode, trace_id = :traceId,
                            claim_owner = NULL, lease_expires_at = NULL
                        WHERE tenant_code = :tenantCode
                          AND tool_code = :toolCode
                          AND idempotency_key = :idempotencyKey
                          AND status = 'RUNNING'
                          AND claim_owner = :claimOwner
                        """, parameters);
    }

    private java.util.Map<String, Object> auditParameters(Audit audit) {
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("tenantCode", audit.tenantCode());
        parameters.put("toolCode", audit.toolCode());
        parameters.put("idempotencyKey", audit.idempotencyKey());
        parameters.put("argumentsHash", audit.argumentsHash());
        parameters.put("status", audit.status());
        parameters.put("outputSnapshot", audit.output());
        parameters.put("errorCode", audit.errorCode());
        parameters.put("traceId", audit.traceId());
        // PostgreSQL cannot infer a JDBC type for java.time.Instant through
        // NamedParameterJdbcTemplate. Bind an explicit SQL timestamp value.
        parameters.put("createdAt", Timestamp.from(audit.createdAt()));
        return parameters;
    }

    private Audit mapAudit(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new Audit(
                resultSet.getString("tenant_code"),
                resultSet.getString("tool_code"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("arguments_hash"),
                resultSet.getString("status"),
                resultSet.getString("output_snapshot"),
                resultSet.getString("error_code"),
                resultSet.getString("trace_id"),
                createdAt == null ? null : createdAt.toInstant());
    }
}

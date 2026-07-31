package io.github.illuseahashmap.workflow.security.serviceauth;

import io.github.illuseahashmap.workflow.common.exception.BusinessException;
import io.github.illuseahashmap.workflow.common.exception.ErrorCode;
import io.github.illuseahashmap.workflow.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ServiceTokenValidationService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowSecurityProperties properties;

    public ServiceTokenValidationService(JdbcTemplate jdbcTemplate, WorkflowSecurityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public ClientValidationResult validate(ServiceTokenPayload payload, String method, String path, String bodySha256) {
        validateRequiredFields(payload);
        validateRequestBinding(payload, method, path, bodySha256);
        validateTimestamp(payload);
        ClientValidationResult client = validateClient(payload);
        TenantContext.TenantInfo tenant = validateTenant(payload.tenantCode());
        validateNonce(payload);
        return new ClientValidationResult(client.clientCode(), client.tokenVersion(), tenant);
    }

    private void validateRequiredFields(ServiceTokenPayload payload) {
        if (!StringUtils.hasText(payload.clientCode())
                || !StringUtils.hasText(payload.tenantCode())
                || payload.timestamp() == null
                || !StringUtils.hasText(payload.nonce())
                || !StringUtils.hasText(payload.method())
                || !StringUtils.hasText(payload.path())
                || !StringUtils.hasText(payload.bodySha256())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token payload is incomplete");
        }
    }

    private void validateRequestBinding(ServiceTokenPayload payload, String method, String path, String bodySha256) {
        if (!Objects.equals(payload.method().toUpperCase(), method.toUpperCase())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token method mismatch");
        }
        if (!Objects.equals(payload.path(), path)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token path mismatch");
        }
        if (!Objects.equals(payload.bodySha256(), bodySha256)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token body hash mismatch");
        }
    }

    private void validateTimestamp(ServiceTokenPayload payload) {
        long now = Instant.now().getEpochSecond();
        long diff = Math.abs(now - payload.timestamp());
        if (diff > properties.getReplayWindowSeconds()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token timestamp expired");
        }
    }

    private ClientValidationResult validateClient(ServiceTokenPayload payload) {
        List<ClientValidationResult> clients = jdbcTemplate.query("""
                SELECT client_code, token_version
                FROM workflow_service_client
                WHERE client_code = ? AND enabled = 1
                """, (rs, rowNum) -> new ClientValidationResult(
                rs.getString("client_code"),
                rs.getInt("token_version"),
                null
        ), payload.clientCode());
        if (clients.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow service client is disabled or missing");
        }
        ClientValidationResult client = clients.getFirst();
        if (payload.tokenVersion() != null && payload.tokenVersion() != client.tokenVersion()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token version is invalid");
        }
        return client;
    }

    private TenantContext.TenantInfo validateTenant(String tenantCode) {
        List<TenantContext.TenantInfo> tenants = jdbcTemplate.query("""
                SELECT tenant_id, tenant_code, tenant_name
                FROM workflow_tenant
                WHERE tenant_code = ? AND enabled = 1
                """, (rs, rowNum) -> new TenantContext.TenantInfo(
                rs.getString("tenant_id"),
                rs.getString("tenant_code"),
                rs.getString("tenant_name")
        ), tenantCode);
        if (tenants.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow tenant is disabled or missing");
        }
        return tenants.getFirst();
    }

    private void validateNonce(ServiceTokenPayload payload) {
        Instant expiresAt = Instant.ofEpochSecond(payload.timestamp()).plusSeconds(properties.getReplayWindowSeconds());
        jdbcTemplate.update("DELETE FROM workflow_service_token_nonce WHERE expires_at < CURRENT_TIMESTAMP");
        try {
            jdbcTemplate.update("""
                    INSERT INTO workflow_service_token_nonce (client_code, nonce, expires_at)
                    VALUES (?, ?, ?)
                    """, payload.clientCode(), payload.nonce(), Timestamp.from(expiresAt));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token nonce was already used");
        }
    }

    public record ClientValidationResult(String clientCode, int tokenVersion, TenantContext.TenantInfo tenantInfo) {
    }
}

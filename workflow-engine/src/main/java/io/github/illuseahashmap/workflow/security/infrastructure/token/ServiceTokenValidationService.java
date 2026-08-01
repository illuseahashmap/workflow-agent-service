package io.github.illuseahashmap.workflow.security.infrastructure.token;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.security.application.port.ServiceClientRepository;
import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

@Service
public class ServiceTokenValidationService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ServiceClientRepository clientRepository;
    private final JdbcTemplate jdbcTemplate;
    private final WorkflowSecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ServiceTokenValidationService(ServiceClientRepository clientRepository,
                                         JdbcTemplate jdbcTemplate,
                                         WorkflowSecurityProperties properties,
                                         ObjectMapper objectMapper) {
        this.clientRepository = clientRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ServiceClient loadClient(String clientCode) {
        if (!StringUtils.hasText(clientCode)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow client code is missing");
        }
        ServiceClient client = clientRepository.findByClientCode(clientCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED,
                        "Workflow service client is disabled or missing"));
        if (!client.enabled() || client.isExpired(OffsetDateTime.now())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow service client is disabled or expired");
        }
        return client;
    }

    public ClientValidationResult validate(ServiceTokenPayload payload,
                                           ServiceClient client,
                                           String method,
                                           String path,
                                           String bodySha256) {
        validateRequiredFields(payload);
        if (!Objects.equals(client.clientCode(), payload.clientCode())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token client mismatch");
        }
        if (!Objects.equals(payload.tokenVersion(), client.secretVersion())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token version is invalid");
        }
        validateRequestBinding(payload, method, path, bodySha256);
        validateTimestamp(payload);
        validateScope(client.allowedTenantCodes(), payload.tenantCode(), false);
        validateScope(client.allowedPaths(), path, true);
        TenantContext.TenantInfo tenant = validateTenant(payload.tenantCode());
        validateNonce(payload);
        return new ClientValidationResult(client.clientCode(), client.secretVersion(), tenant);
    }

    private void validateRequiredFields(ServiceTokenPayload payload) {
        if (payload == null
                || !StringUtils.hasText(payload.clientCode())
                || !StringUtils.hasText(payload.tenantCode())
                || payload.timestamp() == null
                || !StringUtils.hasText(payload.nonce())
                || !StringUtils.hasText(payload.method())
                || !StringUtils.hasText(payload.path())
                || !StringUtils.hasText(payload.bodySha256())
                || payload.tokenVersion() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token payload is incomplete");
        }
    }

    private void validateRequestBinding(ServiceTokenPayload payload, String method, String path, String bodySha256) {
        if (!payload.method().equalsIgnoreCase(method)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token method mismatch");
        }
        if (!Objects.equals(payload.path(), path)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token path mismatch");
        }
        if (!MessageDigestSupport.constantTimeEquals(payload.bodySha256(), bodySha256)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token body hash mismatch");
        }
    }

    private void validateTimestamp(ServiceTokenPayload payload) {
        long now = Instant.now().getEpochSecond();
        long timestamp = payload.timestamp();
        if (timestamp < now - properties.getReplayWindowSeconds()
                || timestamp > now + properties.getClockSkewSeconds()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow token timestamp expired");
        }
    }

    private void validateScope(String configuredScopes, String requestedValue, boolean pathScope) {
        List<String> scopes = parseScopes(configuredScopes);
        boolean allowed = scopes.stream().anyMatch(scope -> "*".equals(scope)
                || (pathScope ? pathMatcher.match(scope, requestedValue) : scope.equals(requestedValue)));
        if (!allowed) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    pathScope ? "Workflow client cannot access this path" : "Workflow client cannot access this tenant");
        }
    }

    private List<String> parseScopes(String configuredScopes) {
        if (!StringUtils.hasText(configuredScopes)) {
            return List.of();
        }
        String normalized = configuredScopes.trim();
        if (normalized.startsWith("[")) {
            try {
                return objectMapper.readValue(normalized, STRING_LIST_TYPE).stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList();
            } catch (Exception exception) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Workflow client scope configuration is invalid");
            }
        }
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private TenantContext.TenantInfo validateTenant(String tenantCode) {
        List<TenantContext.TenantInfo> tenants = jdbcTemplate.query("""
                SELECT tenant_id, tenant_code, tenant_name
                FROM workflow_tenant
                WHERE tenant_code = ? AND enabled = 1
                """, (resultSet, rowNumber) -> new TenantContext.TenantInfo(
                resultSet.getString("tenant_id"),
                resultSet.getString("tenant_code"),
                resultSet.getString("tenant_name")
        ), tenantCode);
        if (tenants.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Workflow tenant is disabled or missing");
        }
        return tenants.getFirst();
    }

    private void validateNonce(ServiceTokenPayload payload) {
        Instant expiresAt = Instant.now().plusSeconds(properties.getReplayWindowSeconds());
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

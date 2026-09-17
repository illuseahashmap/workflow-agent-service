package io.github.illuseahashmap.agent.provider.infrastructure.persistence;

import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialCipher;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAgentCredentialResolver implements AgentCredentialResolver {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AgentCredentialCipher credentialCipher;

    public JdbcAgentCredentialResolver(
            NamedParameterJdbcTemplate jdbcTemplate,
            AgentCredentialCipher credentialCipher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public String resolve(String tenantCode, long providerId) {
        String ciphertext = jdbcTemplate.query("""
                        SELECT secret_ciphertext
                        FROM agent_credential
                        WHERE tenant_code = :tenantCode AND provider_id = :providerId
                        """,
                Map.of("tenantCode", tenantCode, "providerId", providerId),
                (resultSet, rowNum) -> resultSet.getString("secret_ciphertext"))
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.BAD_REQUEST, "Provider credential is not configured"));
        return credentialCipher.decrypt(tenantCode, providerId, ciphertext);
    }
}

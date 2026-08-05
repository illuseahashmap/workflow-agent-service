package io.github.illuseahashmap.workflow.security.infrastructure.persistence;

import io.github.illuseahashmap.workflow.security.application.port.ServiceClientRepository;
import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcServiceClientRepository implements ServiceClientRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcServiceClientRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ServiceClient> findByClientCode(String clientCode) {
        List<ServiceClient> clients = jdbcTemplate.query("""
                SELECT client_code, secret_key_ref, secret_ciphertext, secret_version,
                       allowed_tenant_codes, allowed_paths, enabled, expires_at
                FROM workflow_service_client
                WHERE client_code = ?
                """, (resultSet, rowNumber) -> new ServiceClient(
                resultSet.getString("client_code"),
                resultSet.getString("secret_key_ref"),
                resultSet.getString("secret_ciphertext"),
                resultSet.getInt("secret_version"),
                resultSet.getString("allowed_tenant_codes"),
                resultSet.getString("allowed_paths"),
                resultSet.getInt("enabled") == 1,
                resultSet.getObject("expires_at", java.time.OffsetDateTime.class)
        ), clientCode);
        return clients.stream().findFirst();
    }
}

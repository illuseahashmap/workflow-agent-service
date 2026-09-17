package io.github.illuseahashmap.workflow.auth.infrastructure.persistence;

import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthTenantRepository implements AuthTenantRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuthTenantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<AuthTenant> findByTenantCode(String tenantCode) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tenant_code, tenant_name, enabled
                        FROM workflow_tenant
                        WHERE tenant_code = :tenantCode
                        """)
                .param("tenantCode", tenantCode)
                .query(this::mapRow)
                .optional();
    }

    private AuthTenant mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuthTenant(
                rs.getString("tenant_id"),
                rs.getString("tenant_code"),
                rs.getString("tenant_name"),
                rs.getInt("enabled") == 1
        );
    }
}

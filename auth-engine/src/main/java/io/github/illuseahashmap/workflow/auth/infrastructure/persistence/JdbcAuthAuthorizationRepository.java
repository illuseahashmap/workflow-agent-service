package io.github.illuseahashmap.workflow.auth.infrastructure.persistence;

import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthAuthorizationRepository implements AuthAuthorizationRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuthAuthorizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void grantRole(String userId, String tenantCode, String roleCode) {
        jdbcClient.sql("""
                        INSERT INTO auth_user_role (user_id, tenant_code, role_code)
                        VALUES (:userId, :tenantCode, :roleCode)
                        ON CONFLICT (user_id, tenant_code, role_code) DO NOTHING
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .param("roleCode", roleCode)
                .update();
    }

    @Override
    public Set<String> findRoleCodes(String userId, String tenantCode) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT role_code
                        FROM auth_user_role
                        WHERE user_id = :userId AND tenant_code = :tenantCode
                        ORDER BY role_code
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .query(String.class)
                .list());
    }

    @Override
    public Set<String> findPermissionCodes(String userId, String tenantCode) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT DISTINCT rp.permission_code
                        FROM auth_user_role ur
                        JOIN auth_role_permission rp
                          ON rp.tenant_code = ur.tenant_code
                         AND rp.role_code = ur.role_code
                        WHERE ur.user_id = :userId AND ur.tenant_code = :tenantCode
                        ORDER BY rp.permission_code
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .query(String.class)
                .list());
    }
}

package io.github.illuseahashmap.workflow.auth.infrastructure.persistence;

import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthMembershipRepository implements AuthMembershipRepository {

    private static final String SELECT_MEMBERSHIP = """
            SELECT membership.user_id, tenant.tenant_id, tenant.tenant_code, tenant.tenant_name,
                   membership.enabled AS membership_enabled, tenant.enabled AS tenant_enabled,
                   membership.joined_at
            FROM auth_user_tenant membership
            JOIN workflow_tenant tenant ON tenant.tenant_code = membership.tenant_code
            """;

    private final JdbcClient jdbcClient;

    public JdbcAuthMembershipRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<TenantMembership> findByUserId(String userId) {
        return jdbcClient.sql(SELECT_MEMBERSHIP + " WHERE membership.user_id = :userId ORDER BY tenant.tenant_name")
                .param("userId", userId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public Optional<TenantMembership> find(String userId, String tenantCode) {
        return jdbcClient.sql(SELECT_MEMBERSHIP + """
                        WHERE membership.user_id = :userId AND membership.tenant_code = :tenantCode
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<TenantMember> findMembers(String tenantCode, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        return jdbcClient.sql("""
                        SELECT users.user_id, users.username, users.display_name,
                               users.enabled AS user_enabled, membership.enabled AS membership_enabled,
                               ARRAY(
                                   SELECT assignment.role_code
                                   FROM auth_user_role assignment
                                   JOIN auth_role role
                                     ON role.tenant_code = assignment.tenant_code
                                    AND role.role_code = assignment.role_code
                                    AND role.enabled = 1
                                   WHERE assignment.user_id = users.user_id
                                     AND assignment.tenant_code = :tenantCode
                                   ORDER BY assignment.role_code
                               ) AS tenant_role_codes,
                               ARRAY(
                                   SELECT assignment.role_code
                                   FROM auth_user_role assignment
                                   JOIN auth_role role
                                     ON role.tenant_code = assignment.tenant_code
                                    AND role.role_code = assignment.role_code
                                    AND role.enabled = 1
                                   WHERE assignment.user_id = users.user_id
                                     AND assignment.tenant_code = '*'
                                   ORDER BY assignment.role_code
                               ) AS global_role_codes,
                               membership.joined_at
                        FROM auth_user_tenant membership
                        JOIN auth_user users ON users.user_id = membership.user_id
                        WHERE membership.tenant_code = :tenantCode
                          AND (:keyword = '' OR users.username ILIKE :pattern OR users.display_name ILIKE :pattern)
                        ORDER BY users.display_name, users.username
                        """)
                .param("tenantCode", tenantCode)
                .param("keyword", normalizedKeyword)
                .param("pattern", "%" + normalizedKeyword + "%")
                .query((resultSet, rowNumber) -> new TenantMember(
                        resultSet.getString("user_id"),
                        resultSet.getString("username"),
                        resultSet.getString("display_name"),
                        resultSet.getInt("user_enabled") == 1,
                        resultSet.getInt("membership_enabled") == 1,
                        readStringSet(resultSet, "tenant_role_codes"),
                        readStringSet(resultSet, "global_role_codes"),
                        resultSet.getObject("joined_at", java.time.OffsetDateTime.class)))
                .list();
    }

    @Override
    public void add(String userId, String tenantCode) {
        jdbcClient.sql("""
                        INSERT INTO auth_user_tenant (user_id, tenant_code)
                        VALUES (:userId, :tenantCode)
                        ON CONFLICT (user_id, tenant_code)
                        DO UPDATE SET enabled = 1, updated_at = CURRENT_TIMESTAMP
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .update();
    }

    @Override
    public void updateEnabled(String userId, String tenantCode, boolean enabled) {
        jdbcClient.sql("""
                        UPDATE auth_user_tenant
                        SET enabled = :enabled, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId AND tenant_code = :tenantCode
                        """)
                .param("enabled", enabled ? 1 : 0)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .update();
    }

    private TenantMembership mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TenantMembership(
                resultSet.getString("user_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("tenant_code"),
                resultSet.getString("tenant_name"),
                resultSet.getInt("membership_enabled") == 1,
                resultSet.getInt("tenant_enabled") == 1,
                resultSet.getObject("joined_at", java.time.OffsetDateTime.class)
        );
    }

    private Set<String> readStringSet(ResultSet resultSet, String column) throws SQLException {
        Object array = resultSet.getArray(column).getArray();
        if (array instanceof String[] values) {
            return Set.copyOf(new LinkedHashSet<>(Arrays.asList(values)));
        }
        return Set.of();
    }
}

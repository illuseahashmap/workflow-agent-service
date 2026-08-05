package io.github.illuseahashmap.workflow.auth.infrastructure.persistence;

import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthUserRepository implements AuthUserRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuthUserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean existsByUsername(String username) {
        Integer count = jdbcClient.sql("SELECT COUNT(1) FROM auth_user WHERE username = :username")
                .param("username", username)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    @Override
    public Optional<AuthUser> findByUsername(String username) {
        return jdbcClient.sql("""
                        SELECT id, user_id, username, display_name, password_hash, tenant_code,
                               enabled, created_at, updated_at
                        FROM auth_user
                        WHERE username = :username
                        """)
                .param("username", username)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public Optional<AuthUser> findByUserId(String userId) {
        return jdbcClient.sql("""
                        SELECT id, user_id, username, display_name, password_hash, tenant_code,
                               enabled, created_at, updated_at
                        FROM auth_user
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public AuthUser save(String userId, String username, String displayName, String passwordHash, String tenantCode) {
        return jdbcClient.sql("""
                        INSERT INTO auth_user (user_id, username, display_name, password_hash, tenant_code)
                        VALUES (:userId, :username, :displayName, :passwordHash, :tenantCode)
                        RETURNING id, user_id, username, display_name, password_hash, tenant_code,
                                  enabled, created_at, updated_at
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("displayName", displayName)
                .param("passwordHash", passwordHash)
                .param("tenantCode", tenantCode)
                .query(this::mapRow)
                .single();
    }

    private AuthUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuthUser(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getString("tenant_code"),
                rs.getInt("enabled") == 1,
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class)
        );
    }
}

package io.github.illuseahashmap.workflow.auth.domain;

import java.util.Optional;

public interface AuthUserRepository {

    boolean existsByUsername(String username);

    Optional<AuthUser> findByUsername(String username);

    Optional<AuthUser> findByUserId(String userId);

    AuthUser save(String userId, String username, String displayName, String passwordHash, String tenantCode);
}

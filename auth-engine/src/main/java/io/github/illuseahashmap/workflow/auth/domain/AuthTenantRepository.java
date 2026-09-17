package io.github.illuseahashmap.workflow.auth.domain;

import java.util.Optional;

public interface AuthTenantRepository {

    Optional<AuthTenant> findByTenantCode(String tenantCode);

    record AuthTenant(String tenantId, String tenantCode, String tenantName, boolean enabled) {
    }
}

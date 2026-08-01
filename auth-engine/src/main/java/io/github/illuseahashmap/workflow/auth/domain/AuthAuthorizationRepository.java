package io.github.illuseahashmap.workflow.auth.domain;

import java.util.Set;

public interface AuthAuthorizationRepository {

    void grantRole(String userId, String tenantCode, String roleCode);

    Set<String> findRoleCodes(String userId, String tenantCode);

    Set<String> findPermissionCodes(String userId, String tenantCode);
}

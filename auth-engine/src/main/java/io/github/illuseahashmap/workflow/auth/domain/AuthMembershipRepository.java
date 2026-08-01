package io.github.illuseahashmap.workflow.auth.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AuthMembershipRepository {

    List<TenantMembership> findByUserId(String userId);

    Optional<TenantMembership> find(String userId, String tenantCode);

    List<TenantMember> findMembers(String tenantCode, String keyword);

    void add(String userId, String tenantCode);

    void updateEnabled(String userId, String tenantCode, boolean enabled);

    record TenantMembership(
            String userId,
            String tenantId,
            String tenantCode,
            String tenantName,
            boolean membershipEnabled,
            boolean tenantEnabled,
            OffsetDateTime joinedAt
    ) {
    }

    record TenantMember(
            String userId,
            String username,
            String displayName,
            boolean userEnabled,
            boolean membershipEnabled,
            Set<String> tenantRoleCodes,
            Set<String> globalRoleCodes,
            OffsetDateTime joinedAt
    ) {
    }
}

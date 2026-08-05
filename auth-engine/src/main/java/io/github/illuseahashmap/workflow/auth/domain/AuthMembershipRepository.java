package io.github.illuseahashmap.workflow.auth.domain;

import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AuthMembershipRepository {

    List<TenantMembership> findByUserId(String userId);

    Optional<TenantMembership> find(String userId, String tenantCode);

    List<TenantMember> findMembers(String tenantCode, String keyword);

    PageSlice<TenantMember> pageEnabledMembers(
            String tenantCode, String keyword, int pageNumber, int pageSize);

    Set<String> findEnabledUsernames(String tenantCode, Collection<String> usernames);

    List<UserAvailability> findUserAvailability(String tenantCode, Collection<String> usernames);

    void add(String userId, String tenantCode);

    void updateEnabled(String userId, String tenantCode, boolean enabled);

    void lockTenantMemberships(String tenantCode);

    boolean isEnabledMemberWithRole(String userId, String tenantCode, String roleCode);

    long countEnabledMembersWithRole(String tenantCode, String roleCode);

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

    record UserAvailability(
            String username,
            boolean userEnabled,
            boolean membershipExists,
            boolean membershipEnabled
    ) {
    }
}

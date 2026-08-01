package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.AccessManagementService;
import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.PermissionView;
import io.github.illuseahashmap.workflow.auth.application.dto.SaveTenantRoleRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantRoleView;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.PermissionScope;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessManagementServiceImpl implements AccessManagementService {

    private static final Set<String> BUILT_IN_ROLE_CODES = Set.of("USER", "TENANT_ADMIN");
    private static final String TENANT_ADMIN_ROLE_CODE = "TENANT_ADMIN";

    private final AuthUserRepository userRepository;
    private final AuthMembershipRepository membershipRepository;
    private final AuthAuthorizationRepository authorizationRepository;
    private final CurrentPrincipalProvider principalProvider;

    public AccessManagementServiceImpl(AuthUserRepository userRepository,
                                       AuthMembershipRepository membershipRepository,
                                       AuthAuthorizationRepository authorizationRepository,
                                       CurrentPrincipalProvider principalProvider) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.authorizationRepository = authorizationRepository;
        this.principalProvider = principalProvider;
    }

    @Override
    public List<TenantMemberView> members(String keyword) {
        String tenantCode = currentTenantCode();
        return membershipRepository.findMembers(tenantCode, keyword).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public TenantMemberView addMember(AddTenantMemberRequest request) {
        String tenantCode = currentTenantCode();
        AuthUser user = userRepository.findByUsername(request.username().trim().toLowerCase())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User does not exist"));
        membershipRepository.add(user.userId(), tenantCode);
        Set<String> roles = normalizeRoles(request.roleCodes());
        authorizationRepository.replaceUserRoles(user.userId(), tenantCode, roles);
        return members(user.username()).stream()
                .filter(member -> member.userId().equals(user.userId()))
                .findFirst()
                .orElseThrow();
    }

    @Override
    @Transactional
    public void updateMemberRoles(String userId, Set<String> roleCodes) {
        String tenantCode = currentTenantCode();
        requireMembership(userId, tenantCode);
        Set<String> normalizedRoles = normalizeRoles(roleCodes);
        membershipRepository.lockTenantMemberships(tenantCode);
        assertTenantAdministratorRemains(userId, tenantCode, normalizedRoles.contains(TENANT_ADMIN_ROLE_CODE));
        authorizationRepository.replaceUserRoles(userId, tenantCode, normalizedRoles);
    }

    @Override
    @Transactional
    public void updateMemberEnabled(String userId, boolean enabled) {
        String tenantCode = currentTenantCode();
        if (principalProvider.current().principalId().equals(userId) && !enabled) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cannot disable the current membership");
        }
        requireMembership(userId, tenantCode);
        membershipRepository.lockTenantMemberships(tenantCode);
        if (!enabled) {
            assertTenantAdministratorRemains(userId, tenantCode, false);
        }
        membershipRepository.updateEnabled(userId, tenantCode, enabled);
    }

    @Override
    public List<TenantRoleView> roles() {
        return authorizationRepository.findRoles(currentTenantCode()).stream()
                .map(role -> new TenantRoleView(role.roleCode(), role.roleName(), role.description(),
                        role.enabled(), BUILT_IN_ROLE_CODES.contains(role.roleCode()), role.permissions()))
                .toList();
    }

    @Override
    @Transactional
    public TenantRoleView saveRole(SaveTenantRoleRequest request) {
        String tenantCode = currentTenantCode();
        String roleCode = request.roleCode().trim().toUpperCase();
        if ("PLATFORM_ADMIN".equals(roleCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Platform administrator role cannot be modified here");
        }
        if (BUILT_IN_ROLE_CODES.contains(roleCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Built-in roles cannot be modified");
        }
        Set<String> permissions = validateTenantPermissions(request.permissions());
        authorizationRepository.saveRole(tenantCode, roleCode, request.roleName().trim(),
                normalize(request.description()), request.enabled() == null || request.enabled());
        authorizationRepository.replaceRolePermissions(tenantCode, roleCode, permissions);
        return roles().stream().filter(role -> role.roleCode().equals(roleCode)).findFirst().orElseThrow();
    }

    @Override
    public List<PermissionView> permissions() {
        return authorizationRepository.findPermissions().stream()
                .filter(permission -> permission.scope() == PermissionScope.TENANT)
                .map(permission -> new PermissionView(permission.permissionCode(), permission.permissionName(),
                        permission.description()))
                .toList();
    }

    private TenantMemberView toView(AuthMembershipRepository.TenantMember member) {
        return new TenantMemberView(member.userId(), member.username(), member.displayName(),
                member.userEnabled() && member.membershipEnabled(),
                member.tenantRoleCodes(), member.globalRoleCodes(), member.joinedAt());
    }

    private void requireMembership(String userId, String tenantCode) {
        membershipRepository.find(userId, tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Tenant membership does not exist"));
    }

    private void assertTenantAdministratorRemains(String userId,
                                                  String tenantCode,
                                                  boolean remainsTenantAdministrator) {
        if (remainsTenantAdministrator
                || !membershipRepository.isEnabledMemberWithRole(
                        userId, tenantCode, TENANT_ADMIN_ROLE_CODE)) {
            return;
        }
        if (membershipRepository.countEnabledMembersWithRole(tenantCode, TENANT_ADMIN_ROLE_CODE) <= 1) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "At least one enabled tenant administrator must remain");
        }
    }

    private Set<String> normalizeRoles(Set<String> roles) {
        Set<String> normalized = roles == null || roles.isEmpty()
                ? Set.of("USER")
                : roles.stream().map(String::trim).map(String::toUpperCase)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> available = authorizationRepository.findRoles(currentTenantCode())
                .stream().filter(AuthAuthorizationRepository.RoleDefinition::enabled)
                .map(AuthAuthorizationRepository.RoleDefinition::roleCode)
                .collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "One or more roles do not exist in the current tenant");
        }
        return Set.copyOf(normalized);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Set<String> validateTenantPermissions(Set<String> requestedPermissions) {
        Set<String> normalized = requestedPermissions == null
                ? Set.of()
                : requestedPermissions.stream().map(String::trim).collect(Collectors.toUnmodifiableSet());
        Map<String, AuthAuthorizationRepository.PermissionDefinition> definitions = authorizationRepository
                .findPermissions().stream()
                .collect(Collectors.toMap(
                        AuthAuthorizationRepository.PermissionDefinition::permissionCode,
                        Function.identity()));
        if (!definitions.keySet().containsAll(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "One or more permissions do not exist");
        }
        if (normalized.stream().map(definitions::get)
                .anyMatch(permission -> permission.scope() != PermissionScope.TENANT)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Platform permissions cannot be assigned to a tenant role");
        }
        return normalized;
    }

    private String currentTenantCode() {
        return principalProvider.current().tenantCode();
    }
}

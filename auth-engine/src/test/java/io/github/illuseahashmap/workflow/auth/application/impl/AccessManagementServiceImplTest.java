package io.github.illuseahashmap.workflow.auth.application.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.dto.SaveTenantRoleRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.auth.domain.PermissionScope;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessManagementServiceImplTest {

    private final AuthUserRepository userRepository = mock(AuthUserRepository.class);
    private final AuthMembershipRepository membershipRepository = mock(AuthMembershipRepository.class);
    private final AuthAuthorizationRepository authorizationRepository = mock(AuthAuthorizationRepository.class);
    private final CurrentPrincipalProvider principalProvider = () -> new CurrentPrincipal(
            "USER", "user-1", "tenant-admin", "Tenant Admin", "tenant-a",
            Set.of("TENANT_ADMIN"), Set.of("role:manage"));
    private final AccessManagementServiceImpl service = new AccessManagementServiceImpl(
            userRepository, membershipRepository, authorizationRepository, principalProvider);

    @Test
    void tenantAdministratorCannotGrantPlatformPermissionToCustomRole() {
        when(authorizationRepository.findPermissions()).thenReturn(List.of(
                new AuthAuthorizationRepository.PermissionDefinition(
                        "tenant:manage", "Manage tenants", null, PermissionScope.PLATFORM)));
        SaveTenantRoleRequest request = new SaveTenantRoleRequest(
                "custom-admin", "Custom Admin", null, true, Set.of("tenant:manage"));

        assertThatThrownBy(() -> service.saveRole(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        verify(authorizationRepository, never()).saveRole(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void unknownPermissionIsRejectedBeforeRoleIsSaved() {
        when(authorizationRepository.findPermissions()).thenReturn(List.of());
        SaveTenantRoleRequest request = new SaveTenantRoleRequest(
                "custom", "Custom", null, true, Set.of("unknown:permission"));

        assertThatThrownBy(() -> service.saveRole(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("One or more permissions do not exist");
        verify(authorizationRepository, never()).replaceRolePermissions(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anySet());
    }

    @Test
    void cannotRemoveTheLastEnabledTenantAdministratorRole() {
        prepareTenantAdministratorMembership("user-2");
        when(authorizationRepository.findRoles("tenant-a")).thenReturn(List.of(
                new AuthAuthorizationRepository.RoleDefinition(
                        "USER", "User", null, true, Set.of()),
                new AuthAuthorizationRepository.RoleDefinition(
                        "TENANT_ADMIN", "Tenant Administrator", null, true, Set.of())));

        assertThatThrownBy(() -> service.updateMemberRoles("user-2", Set.of("USER")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("At least one enabled tenant administrator must remain");

        verify(authorizationRepository, never()).replaceUserRoles("user-2", "tenant-a", Set.of("USER"));
    }

    @Test
    void cannotDisableTheLastEnabledTenantAdministrator() {
        prepareTenantAdministratorMembership("user-2");

        assertThatThrownBy(() -> service.updateMemberEnabled("user-2", false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("At least one enabled tenant administrator must remain");

        verify(membershipRepository, never()).updateEnabled("user-2", "tenant-a", false);
    }

    @Test
    void memberManagerCannotGrantTenantAdministratorRole() {
        CurrentPrincipalProvider memberManager = () -> new CurrentPrincipal(
                "USER", "manager-id", "manager", "Manager", "tenant-a",
                Set.of("CUSTOM_MANAGER"), Set.of("member:manage"));
        AccessManagementServiceImpl managerService = new AccessManagementServiceImpl(
                userRepository, membershipRepository, authorizationRepository, memberManager);
        when(userRepository.findByUsername("new-user")).thenReturn(Optional.of(
                new AuthUser(1L, "new-id", "new-user", "New User", "hash",
                        "tenant-a", true, null, null)));
        when(authorizationRepository.findRoles("tenant-a")).thenReturn(List.of(
                new AuthAuthorizationRepository.RoleDefinition(
                        "TENANT_ADMIN", "Tenant Administrator", null, true, Set.of())));

        assertThatThrownBy(() -> managerService.addMember(
                new AddTenantMemberRequest("new-user", Set.of("TENANT_ADMIN"))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only an administrator can grant or revoke the tenant administrator role");

        verify(authorizationRepository, never()).replaceUserRoles(
                "new-id", "tenant-a", Set.of("TENANT_ADMIN"));
    }

    @Test
    void userCannotElevateSelfToTenantAdministrator() {
        CurrentPrincipalProvider selfManager = () -> new CurrentPrincipal(
                "USER", "user-2", "self", "Self", "tenant-a",
                Set.of("CUSTOM_MANAGER"), Set.of("member:manage"));
        AccessManagementServiceImpl selfService = new AccessManagementServiceImpl(
                userRepository, membershipRepository, authorizationRepository, selfManager);
        when(membershipRepository.find("user-2", "tenant-a")).thenReturn(Optional.of(
                new AuthMembershipRepository.TenantMembership(
                        "user-2", "tenant-id", "tenant-a", "Tenant", true, true, null)));
        when(authorizationRepository.findRoles("tenant-a")).thenReturn(List.of(
                new AuthAuthorizationRepository.RoleDefinition(
                        "TENANT_ADMIN", "Tenant Administrator", null, true, Set.of())));

        assertThatThrownBy(() -> selfService.updateMemberRoles("user-2", Set.of("TENANT_ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Users cannot grant the tenant administrator role to themselves");
    }

    @Test
    void memberManagerCannotGrantCustomRoleBeyondOwnPermissionBoundary() {
        CurrentPrincipalProvider memberManager = () -> new CurrentPrincipal(
                "USER", "manager-id", "manager", "Manager", "tenant-a",
                Set.of("MEMBER_MANAGER"), Set.of("member:manage"));
        AccessManagementServiceImpl managerService = new AccessManagementServiceImpl(
                userRepository, membershipRepository, authorizationRepository, memberManager);
        when(membershipRepository.find("user-2", "tenant-a")).thenReturn(Optional.of(
                new AuthMembershipRepository.TenantMembership(
                        "user-2", "tenant-id", "tenant-a", "Tenant", true, true, null)));
        when(authorizationRepository.findRoles("tenant-a")).thenReturn(List.of(
                new AuthAuthorizationRepository.RoleDefinition(
                        "WORKFLOW_DESIGNER", "Workflow Designer", null, true,
                        Set.of("workflow:definition:write"))));

        assertThatThrownBy(() -> managerService.updateMemberRoles(
                "user-2", Set.of("WORKFLOW_DESIGNER")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cannot grant or revoke roles outside the operator permission boundary");

        verify(authorizationRepository, never()).replaceUserRoles(
                "user-2", "tenant-a", Set.of("WORKFLOW_DESIGNER"));
    }

    private void prepareTenantAdministratorMembership(String userId) {
        when(membershipRepository.find(userId, "tenant-a")).thenReturn(Optional.of(
                new AuthMembershipRepository.TenantMembership(
                        userId, "tenant-a-id", "tenant-a", "Tenant A", true, true, null)));
        when(membershipRepository.isEnabledMemberWithRole(userId, "tenant-a", "TENANT_ADMIN"))
                .thenReturn(true);
        when(membershipRepository.countEnabledMembersWithRole("tenant-a", "TENANT_ADMIN"))
                .thenReturn(1L);
    }
}

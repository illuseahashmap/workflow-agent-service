package io.github.illuseahashmap.workflow.auth.application.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.dto.SaveTenantRoleRequest;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.auth.domain.PermissionScope;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
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
}

package io.github.illuseahashmap.workflow.tenant.application.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.AuthTenantProvisioningService;
import io.github.illuseahashmap.workflow.auth.domain.SecurityAuditRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenant;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenantRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantManagementServiceImplTest {

    private final WorkflowTenantRepository repository = mock(WorkflowTenantRepository.class);
    private final AuthTenantProvisioningService provisioningService = mock(AuthTenantProvisioningService.class);
    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final SecurityAuditRepository securityAuditRepository = mock(SecurityAuditRepository.class);
    private final TenantManagementServiceImpl service = new TenantManagementServiceImpl(
            repository, provisioningService, principalProvider, securityAuditRepository);

    @Test
    void tenantIdentifierAndCodeAreImmutable() {
        WorkflowTenant existing = new WorkflowTenant(
                7L, "tenant-id", "tenant-code", "Tenant", null, true, null, null);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(7L,
                new TenantCommand("changed-id", "tenant-code", "Tenant", null, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Tenant id and code cannot be changed");
        verify(repository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cannotDisableLastEnabledTenant() {
        preparePlatformAdministrator("tenant-b");
        WorkflowTenant tenant = tenant(7L, "tenant-a", true);
        when(repository.findById(7L)).thenReturn(Optional.of(tenant));
        when(repository.countEnabled()).thenReturn(1L);

        assertThatThrownBy(() -> service.updateEnabled(7L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("At least one enabled tenant must remain");

        verify(repository, never()).updateEnabled(7L, false);
    }

    @Test
    void cannotDisableCurrentTenant() {
        preparePlatformAdministrator("tenant-a");
        when(repository.findById(7L)).thenReturn(Optional.of(tenant(7L, "tenant-a", true)));

        assertThatThrownBy(() -> service.updateEnabled(7L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Switch to another enabled tenant before disabling the current tenant");
    }

    @Test
    void platformAdministratorCanRestoreTenantAndOperationIsAudited() {
        preparePlatformAdministrator("tenant-a");
        when(repository.findById(7L)).thenReturn(Optional.of(tenant(7L, "tenant-b", false)));

        service.restore(7L);

        verify(repository).updateEnabled(7L, true);
        verify(securityAuditRepository).record(
                "TENANT_RESTORED", "admin-id", "tenant-b", null,
                "tenant-id", "SUCCESS", "Tenant lifecycle state changed by platform administrator");
    }

    private void preparePlatformAdministrator(String tenantCode) {
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "admin-id", "admin", "Admin", tenantCode,
                Set.of("PLATFORM_ADMIN"), Set.of("tenant:manage")));
    }

    private WorkflowTenant tenant(long id, String tenantCode, boolean enabled) {
        return new WorkflowTenant(id, "tenant-id", tenantCode, "Tenant", null, enabled, null, null);
    }
}

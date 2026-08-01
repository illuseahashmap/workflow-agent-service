package io.github.illuseahashmap.workflow.tenant.application.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.AuthTenantProvisioningService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenant;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenantRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenantManagementServiceImplTest {

    private final WorkflowTenantRepository repository = mock(WorkflowTenantRepository.class);
    private final AuthTenantProvisioningService provisioningService = mock(AuthTenantProvisioningService.class);
    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final TenantManagementServiceImpl service = new TenantManagementServiceImpl(
            repository, provisioningService, principalProvider);

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
}

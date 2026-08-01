package io.github.illuseahashmap.workflow.tenant.application.impl;

import io.github.illuseahashmap.workflow.auth.application.AuthTenantProvisioningService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.tenant.application.TenantManagementService;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantView;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenant;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenantRepository;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantManagementServiceImpl implements TenantManagementService {

    private final WorkflowTenantRepository tenantRepository;
    private final AuthTenantProvisioningService tenantProvisioningService;
    private final CurrentPrincipalProvider principalProvider;

    public TenantManagementServiceImpl(WorkflowTenantRepository tenantRepository,
                                       AuthTenantProvisioningService tenantProvisioningService,
                                       CurrentPrincipalProvider principalProvider) {
        this.tenantRepository = tenantRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.principalProvider = principalProvider;
    }

    @Override
    public PageResult<TenantView> page(Integer pageNum, Integer pageSize, String keyword, Boolean enabled) {
        int normalizedPageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        PageSlice<WorkflowTenant> page = tenantRepository.page(
                normalizedPageNum, normalizedPageSize, normalize(keyword), enabled);
        return new PageResult<>(page.total(), page.pageNumber(), page.pageSize(),
                page.items().stream().map(this::toView).toList());
    }

    @Override
    public List<TenantView> listEnabled() {
        return tenantRepository.findEnabled().stream().map(this::toView).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantView create(TenantCommand command) {
        try {
            WorkflowTenant tenant = tenantRepository.save(toTenant(null, command));
            tenantProvisioningService.provision(
                    tenant.tenantCode(), principalProvider.current().principalId());
            return toView(tenant);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "Tenant id or code already exists");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(long id, TenantCommand command) {
        WorkflowTenant existing = requireTenant(id);
        if (!existing.tenantId().equals(command.tenantId().trim())
                || !existing.tenantCode().equals(command.tenantCode().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant id and code cannot be changed");
        }
        try {
            tenantRepository.update(toTenant(id, command));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "Tenant id or code already exists");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabled(long id, boolean enabled) {
        requireTenant(id);
        tenantRepository.updateEnabled(id, enabled);
    }

    private WorkflowTenant toTenant(Long id, TenantCommand command) {
        return new WorkflowTenant(
                id,
                command.tenantId().trim(),
                command.tenantCode().trim(),
                command.tenantName().trim(),
                normalize(command.description()),
                command.enabled() == null || command.enabled(),
                null,
                null);
    }

    private WorkflowTenant requireTenant(long id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Workflow tenant does not exist"));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TenantView toView(WorkflowTenant tenant) {
        return new TenantView(
                tenant.id(), tenant.tenantId(), tenant.tenantCode(), tenant.tenantName(), tenant.description(),
                tenant.enabled(), tenant.createdAt(), tenant.updatedAt());
    }
}

package io.github.illuseahashmap.workflow.tenant.application.impl;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.tenant.application.TenantManagementService;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenant;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenantRepository;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantManagementServiceImpl implements TenantManagementService {

    private final WorkflowTenantRepository tenantRepository;

    public TenantManagementServiceImpl(WorkflowTenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public PageResult<WorkflowTenant> page(Integer pageNum, Integer pageSize, String keyword, Boolean enabled) {
        int normalizedPageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return tenantRepository.page(normalizedPageNum, normalizedPageSize, normalize(keyword), enabled);
    }

    @Override
    public List<WorkflowTenant> listEnabled() {
        return tenantRepository.findEnabled();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowTenant create(TenantCommand command) {
        try {
            return tenantRepository.save(toTenant(null, command));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "Tenant id or code already exists");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(long id, TenantCommand command) {
        requireTenant(id);
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

    private void requireTenant(long id) {
        tenantRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Workflow tenant does not exist"));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

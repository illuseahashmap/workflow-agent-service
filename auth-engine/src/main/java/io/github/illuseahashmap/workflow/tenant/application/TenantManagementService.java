package io.github.illuseahashmap.workflow.tenant.application;

import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenant;
import java.util.List;

public interface TenantManagementService {

    PageResult<WorkflowTenant> page(Integer pageNum, Integer pageSize, String keyword, Boolean enabled);

    List<WorkflowTenant> listEnabled();

    WorkflowTenant create(TenantCommand command);

    void update(long id, TenantCommand command);

    void updateEnabled(long id, boolean enabled);
}

package io.github.illuseahashmap.workflow.tenant.domain;

import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
import java.util.Optional;

public interface WorkflowTenantRepository {

    PageResult<WorkflowTenant> page(int pageNum, int pageSize, String keyword, Boolean enabled);

    List<WorkflowTenant> findEnabled();

    Optional<WorkflowTenant> findById(long id);

    WorkflowTenant save(WorkflowTenant tenant);

    void update(WorkflowTenant tenant);

    void updateEnabled(long id, boolean enabled);
}

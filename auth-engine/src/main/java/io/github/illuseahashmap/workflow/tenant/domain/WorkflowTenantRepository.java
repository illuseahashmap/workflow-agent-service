package io.github.illuseahashmap.workflow.tenant.domain;

import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.util.List;
import java.util.Optional;

public interface WorkflowTenantRepository {

    PageSlice<WorkflowTenant> page(int pageNum, int pageSize, String keyword, Boolean enabled);

    List<WorkflowTenant> findEnabled();

    Optional<WorkflowTenant> findById(long id);

    WorkflowTenant save(WorkflowTenant tenant);

    void update(WorkflowTenant tenant);

    void updateEnabled(long id, boolean enabled);
}

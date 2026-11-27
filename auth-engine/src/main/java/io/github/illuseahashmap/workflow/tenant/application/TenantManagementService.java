package io.github.illuseahashmap.workflow.tenant.application;

import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantView;
import java.util.List;

public interface TenantManagementService {

    PageResult<TenantView> page(Integer pageNum, Integer pageSize, String keyword, Boolean enabled);

    List<TenantView> listEnabled();

    TenantView create(TenantCommand command);

    void update(long id, TenantCommand command);

    void updateEnabled(long id, boolean enabled);

    void restore(long id);
}

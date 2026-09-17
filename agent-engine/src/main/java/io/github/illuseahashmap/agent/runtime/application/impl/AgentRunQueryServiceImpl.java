package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.runtime.application.AgentRunQueryService;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunDetailView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunView;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunQueryRepository;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentRunQueryServiceImpl implements AgentRunQueryService {

    private final AgentRunQueryRepository repository;
    private final TenantProvider tenantProvider;

    public AgentRunQueryServiceImpl(AgentRunQueryRepository repository, TenantProvider tenantProvider) {
        this.repository = repository;
        this.tenantProvider = tenantProvider;
    }

    @Override
    public PageResult<AgentRunView> page(
            Integer pageNum,
            Integer pageSize,
            String keyword,
            String status
    ) {
        int normalizedPageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        PageSlice<AgentRunView> page = repository.page(new AgentRunQueryRepository.PageCriteria(
                normalizedPageNum, normalizedPageSize, tenantProvider.current().tenantCode(),
                StringUtils.hasText(keyword) ? keyword.trim() : null, parseStatus(status)));
        return new PageResult<>(page.total(), page.pageNumber(), page.pageSize(), page.items());
    }

    @Override
    public AgentRunDetailView detail(long runId) {
        if (runId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent run id must be positive");
        }
        return repository.findDetail(tenantProvider.current().tenantCode(), runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent run does not exist"));
    }

    @Override
    public List<AgentRunView> findByProcessInstance(String processInstanceId) {
        if (!StringUtils.hasText(processInstanceId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Process instance id must not be blank");
        }
        return repository.findByProcessInstance(
                tenantProvider.current().tenantCode(), processInstanceId.trim());
    }

    private AgentRunStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return AgentRunStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown Agent run status");
        }
    }
}

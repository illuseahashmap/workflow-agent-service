package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.WorkflowOperationAuditView;
import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditQueryRepository;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WorkflowOperationAuditQueryService {

    private final WorkflowOperationAuditQueryRepository repository;
    private final io.github.illuseahashmap.workflow.shared.context.TenantProvider tenantProvider;

    public WorkflowOperationAuditQueryService(
            WorkflowOperationAuditQueryRepository repository,
            io.github.illuseahashmap.workflow.shared.context.TenantProvider tenantProvider) {
        this.repository = repository;
        this.tenantProvider = tenantProvider;
    }

    public PageResult<WorkflowOperationAuditView> page(
            Integer pageNumber,
            Integer pageSize,
            String eventType,
            String processInstanceId,
            String traceId,
            Instant occurredFrom,
            Instant occurredTo) {
        int normalizedPage = pageNumber == null || pageNumber < 1 ? 1 : pageNumber;
        int normalizedSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Audit time range is invalid");
        }
        PageSlice<WorkflowOperationAuditView> result = repository.page(
                new WorkflowOperationAuditQueryRepository.PageCriteria(
                        normalizedPage, normalizedSize, tenantProvider.current().tenantCode(),
                        normalize(eventType), normalize(processInstanceId), normalize(traceId),
                        occurredFrom, occurredTo));
        return new PageResult<>(result.total(), result.pageNumber(), result.pageSize(), result.items());
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

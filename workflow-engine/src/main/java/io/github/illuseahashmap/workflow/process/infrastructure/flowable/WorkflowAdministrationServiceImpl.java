package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionDiagramView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowAdministrationServiceImpl implements WorkflowAdministrationService {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final JdbcTemplate jdbcTemplate;
    private final ProcessInstanceTransactionExecutor transactionExecutor;
    private final WorkflowDefinitionReadService definitionReadService;
    private final WorkflowInstanceReadService instanceReadService;
    private final TenantProvider tenantProvider;
    private final AssignmentRuleService assignmentRuleService;
    private final WorkflowOperationAuditService auditService;

    public WorkflowAdministrationServiceImpl(RepositoryService repositoryService,
                                             RuntimeService runtimeService,
                                             HistoryService historyService,
                                             JdbcTemplate jdbcTemplate,
                                             ProcessInstanceTransactionExecutor transactionExecutor,
                                             WorkflowDefinitionReadService definitionReadService,
                                             WorkflowInstanceReadService instanceReadService,
                                             TenantProvider tenantProvider,
                                             AssignmentRuleService assignmentRuleService,
                                             WorkflowOperationAuditService auditService) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionExecutor = transactionExecutor;
        this.definitionReadService = definitionReadService;
        this.instanceReadService = instanceReadService;
        this.tenantProvider = tenantProvider;
        this.assignmentRuleService = assignmentRuleService;
        this.auditService = auditService;
    }

    @Override
    public byte[] generateProcessDiagram(String processInstanceId) {
        return instanceReadService.generateDiagram(processInstanceId);
    }

    @Override
    public ProcessDiagramDataView getProcessDiagramData(String processInstanceId) {
        return instanceReadService.diagramData(processInstanceId);
    }

    @Override
    public PageResult<ProcessDefinitionSummaryView> pageProcessDefinitions(
            Integer pageNum, Integer pageSize, String key, String name, String publishStatus) {
        return definitionReadService.page(pageNum, pageSize, key, name, publishStatus);
    }

    @Override
    public PageResult<ProcessInstanceSummaryView> pageProcessInstances(
            Integer pageNum, Integer pageSize, String definitionKey, String definitionName,
            String processInstanceId, String businessKey, String status) {
        return instanceReadService.page(
                pageNum, pageSize, definitionKey, definitionName, processInstanceId, businessKey, status);
    }

    @Override
    public ProcessInstanceDetailView getProcessInstanceDetail(String processInstanceId) {
        return instanceReadService.detail(processInstanceId);
    }

    @Override
    public void terminateProcessInstance(TerminateProcessRequest request) {
        String processInstanceId = request.processInstanceId().trim();
        transactionExecutor.execute(processInstanceId, () -> {
            String tenantId = tenantProvider.current().tenantId();
            ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (instance == null) {
                if (historyService.createHistoricProcessInstanceQuery()
                        .processInstanceTenantId(tenantId)
                        .processInstanceId(processInstanceId)
                        .count() > 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "Process instance is already finished");
                }
                throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
            }
            runtimeService.deleteProcessInstance(processInstanceId, request.reason().trim());
            auditService.record(
                    "PROCESS_TERMINATED", processInstanceId, instance.getProcessDefinitionKey(),
                    null, instance.getBusinessKey(), "RUNNING", "TERMINATED", request.reason());
            return null;
        });
    }

    @Override
    public ProcessDefinitionDiagramView getProcessDefinitionDiagram(
            String processDefinitionKey, Integer version, String processDefinitionId) {
        return definitionReadService.diagram(processDefinitionKey, version, processDefinitionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProcessDefinitions(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .list();
        if (definitions.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        assignmentRuleService.deleteByProcessDefinitionKey(processDefinitionKey);
        definitions.stream().map(ProcessDefinition::getDeploymentId).distinct()
                .forEach(deploymentId -> repositoryService.deleteDeployment(deploymentId, true));
        jdbcTemplate.update("""
                DELETE FROM workflow_active_version WHERE tenant_id = ? AND process_definition_key = ?
                """, tenantId, processDefinitionKey);
        auditService.record(
                "PROCESS_DEFINITIONS_DELETED", null, processDefinitionKey,
                null, processDefinitionKey, "DEPLOYED", "DELETED", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProcessDefinitionVersion(String processDefinitionKey, Integer version) {
        if (version == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Process definition version is required");
        }
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .processDefinitionVersion(version)
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition version does not exist");
        }
        assignmentRuleService.deleteByProcessDefinition(definition.getId());
        repositoryService.deleteDeployment(definition.getDeploymentId(), true);
        jdbcTemplate.update("""
                DELETE FROM workflow_active_version
                WHERE tenant_id = ? AND process_definition_key = ? AND process_definition_id = ?
                """, tenantId, processDefinitionKey, definition.getId());
        auditService.record(
                "PROCESS_DEFINITION_VERSION_DELETED", null, processDefinitionKey,
                null, "version=" + version, "DEPLOYED", "DELETED", null);
    }
}

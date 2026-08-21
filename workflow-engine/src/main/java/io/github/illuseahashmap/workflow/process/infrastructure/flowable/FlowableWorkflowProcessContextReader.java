package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.port.WorkflowProcessContextReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Flowable adapter for the bounded workflow context application port. */
@Component
class FlowableWorkflowProcessContextReader implements WorkflowProcessContextReader {

    private static final Set<String> SENSITIVE_NAME_PARTS = Set.of(
            "password", "token", "secret", "credential", "apikey", "api_key", "privatekey");

    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final JdbcTemplate jdbcTemplate;

    FlowableWorkflowProcessContextReader(RuntimeService runtimeService,
                                         HistoryService historyService,
                                         RepositoryService repositoryService,
                                         TaskService taskService,
                                         JdbcTemplate jdbcTemplate) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.taskService = taskService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<WorkflowProcessContext> read(String tenantCode, String processInstanceId) {
        String tenantId = jdbcTemplate.query("""
                        SELECT tenant_id
                        FROM workflow_tenant
                        WHERE tenant_code = ? AND enabled = 1
                        """,
                (resultSet, rowNum) -> resultSet.getString("tenant_id"), tenantCode)
                .stream()
                .findFirst()
                .orElse(null);
        if (tenantId == null) {
            return Optional.empty();
        }
        ProcessInstance running = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceId(processInstanceId)
                .singleResult();
        if (running != null) {
            return Optional.of(context(
                    processInstanceId, running.getProcessDefinitionId(), "RUNNING", running.getBusinessKey(), true));
        }
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceId(processInstanceId)
                .singleResult();
        if (historic == null) {
            return Optional.empty();
        }
        return Optional.of(context(
                processInstanceId, historic.getProcessDefinitionId(), "FINISHED", historic.getBusinessKey(), false));
    }

    private WorkflowProcessContext context(String processInstanceId, String processDefinitionId,
                                           String status, String businessKey, boolean running) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
        List<ActiveTask> tasks = running
                ? taskService.createTaskQuery().processInstanceId(processInstanceId).list().stream()
                .map(this::activeTask).toList()
                : List.of();
        Map<String, Object> variables = new LinkedHashMap<>();
        historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).list().stream()
                .filter(variable -> safeVariableName(variable.getVariableName()))
                .forEach(variable -> variables.put(variable.getVariableName(), variable.getValue()));
        if (running) {
            runtimeService.createVariableInstanceQuery().processInstanceId(processInstanceId).list().stream()
                    .filter(variable -> safeVariableName(variable.getName()))
                    .forEach(variable -> variables.put(variable.getName(), variable.getValue()));
        }
        return new WorkflowProcessContext(
                processInstanceId,
                definition == null ? null : definition.getKey(),
                definition == null ? null : definition.getName(),
                businessKey,
                status,
                tasks,
                variables);
    }

    private ActiveTask activeTask(Task task) {
        return new ActiveTask(task.getTaskDefinitionKey(), task.getName(), task.getAssignee());
    }

    private boolean safeVariableName(String name) {
        if (ProcessVariablePolicy.isInternalVariable(name)) {
            return false;
        }
        String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return SENSITIVE_NAME_PARTS.stream().noneMatch(normalized::contains);
    }
}

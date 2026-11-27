package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.AgentInputRequirementDeriver;
import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.WorkflowInteractionService;
import io.github.illuseahashmap.workflow.process.application.dto.InteractionDataFieldView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInteractionView;
import io.github.illuseahashmap.workflow.process.application.dto.TaskInteractionRequest;
import io.github.illuseahashmap.workflow.process.application.port.AgentVersionCatalog;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Resolves the next Agent frontier and exposes its typed business input contract. */
@Service
public class FlowableWorkflowInteractionService implements WorkflowInteractionService {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final TenantProvider tenantProvider;
    private final FlowableUserTaskPathResolver pathResolver;
    private final AgentVersionCatalog versionCatalog;
    private final AgentInputRequirementDeriver requirementDeriver;

    public FlowableWorkflowInteractionService(
            RepositoryService repositoryService,
            RuntimeService runtimeService,
            TaskService taskService,
            TenantProvider tenantProvider,
            FlowableUserTaskPathResolver pathResolver,
            AgentVersionCatalog versionCatalog,
            AgentInputRequirementDeriver requirementDeriver
    ) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.tenantProvider = tenantProvider;
        this.pathResolver = pathResolver;
        this.versionCatalog = versionCatalog;
        this.requirementDeriver = requirementDeriver;
    }

    @Override
    public ProcessInteractionView startInteraction(ProcessInteractionRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        String processDefinitionId = resolveProcessDefinitionId(
                request.processDefinitionKey(), request.processDefinitionId(), tenant.tenantId());
        Map<String, Object> variables = ProcessVariablePolicy.clientVariables(request.variables());
        return interaction(pathResolver.firstAgentTasks(processDefinitionId, variables), variables, tenant.tenantCode());
    }

    @Override
    public ProcessInteractionView taskInteraction(TaskInteractionRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        Task task = Optional.ofNullable(taskService.createTaskQuery().taskId(request.taskId()).singleResult())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Active task does not exist"));
        if (!tenant.tenantId().equals(task.getTenantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Task belongs to another tenant");
        }
        Map<String, Object> variables = new LinkedHashMap<>(
                runtimeService.getVariables(task.getProcessInstanceId()));
        variables.putAll(ProcessVariablePolicy.clientVariables(request.variables()));
        return interaction(pathResolver.nextAgentTasks(task, variables), variables, tenant.tenantCode());
    }

    private ProcessInteractionView interaction(
            List<FlowElement> agentTasks,
            Map<String, Object> variables,
            String tenantCode
    ) {
        Map<String, InteractionDataFieldView> fields = new LinkedHashMap<>();
        for (FlowElement task : agentTasks) {
            ExtensionElement binding = agentExtension(task)
                    .orElseThrow(() -> invalid("Agent task is missing workflow binding: " + task.getId()));
            long versionId = positiveLong(binding.getAttributeValue(null, "agentVersionId"));
            AgentVersionCatalog.PublishedAgentVersion version = versionCatalog.findPublished(tenantCode, versionId)
                    .orElseThrow(() -> invalid("Published Agent version does not exist: " + versionId));
            String inputMapping = defaultJson(binding.getAttributeValue(null, "inputMapping"));
            String activityName = StringUtils.hasText(task.getName()) ? task.getName().trim() : task.getId();
            for (InteractionDataFieldView field : requirementDeriver.derive(
                    version.inputSchemaJson(), inputMapping, variables, task.getId(), activityName)) {
                fields.merge(field.variablePath(), field, this::mergeCompatible);
            }
        }
        return new ProcessInteractionView(
                List.copyOf(fields.values()),
                agentTasks.stream().map(FlowElement::getId).toList());
    }

    private InteractionDataFieldView mergeCompatible(
            InteractionDataFieldView existing,
            InteractionDataFieldView candidate
    ) {
        if (!existing.dataType().equals(candidate.dataType())) {
            throw invalid("Process variable has incompatible Agent input types: " + existing.variablePath());
        }
        return existing;
    }

    private Optional<ExtensionElement> agentExtension(FlowElement task) {
        if (task.getExtensionElements() == null) {
            return Optional.empty();
        }
        return task.getExtensionElements().values().stream()
                .flatMap(Collection::stream)
                .flatMap(extension -> flatten(extension).stream())
                .filter(this::isAgentExtension)
                .findFirst();
    }

    private List<ExtensionElement> flatten(ExtensionElement extension) {
        var result = new java.util.ArrayList<ExtensionElement>();
        result.add(extension);
        if (extension.getChildElements() != null) {
            extension.getChildElements().values().stream()
                    .flatMap(Collection::stream)
                    .forEach(child -> result.addAll(flatten(child)));
        }
        return result;
    }

    private boolean isAgentExtension(ExtensionElement extension) {
        return "agentTask".equals(extension.getName())
                || "http://workflow-agent.local/bpmn".equals(extension.getNamespace());
    }

    private String resolveProcessDefinitionId(String key, String requestedId, String tenantId) {
        ProcessDefinition definition;
        if (StringUtils.hasText(requestedId)) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(requestedId.trim())
                    .processDefinitionTenantId(tenantId)
                    .singleResult();
            if (definition == null || !key.equals(definition.getKey())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Published process definition does not exist");
            }
        } else {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(key)
                    .processDefinitionTenantId(tenantId)
                    .active()
                    .latestVersion()
                    .singleResult();
            if (definition == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Active process definition does not exist");
            }
        }
        return definition.getId();
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Report a stable contract error below.
        }
        throw invalid("Agent version id must be positive");
    }

    private String defaultJson(String value) {
        return StringUtils.hasText(value) ? value : "{}";
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}

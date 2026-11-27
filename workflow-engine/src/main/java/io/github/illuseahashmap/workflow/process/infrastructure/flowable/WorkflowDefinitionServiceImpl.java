package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.process.application.dto.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

@Service
public class WorkflowDefinitionServiceImpl implements WorkflowDefinitionService {

    private final RepositoryService repositoryService;
    private final JdbcTemplate jdbcTemplate;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;
    private final AgentTaskBindingParser agentTaskBindingParser;
    private final AgentBindingDeploymentValidator agentBindingDeploymentValidator;
    private final WorkflowOperationAuditService auditService;

    public WorkflowDefinitionServiceImpl(RepositoryService repositoryService,
                                         JdbcTemplate jdbcTemplate,
                                         TenantProvider tenantProvider,
                                         CurrentPrincipalProvider principalProvider,
                                         AgentTaskBindingParser agentTaskBindingParser,
                                         AgentBindingDeploymentValidator agentBindingDeploymentValidator,
                                         WorkflowOperationAuditService auditService) {
        this.repositoryService = repositoryService;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
        this.agentTaskBindingParser = agentTaskBindingParser;
        this.agentBindingDeploymentValidator = agentBindingDeploymentValidator;
        this.auditService = auditService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeployProcessResult deploy(DeployProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        agentBindingDeploymentValidator.validate(
                tenant.tenantCode(), agentTaskBindingParser.parse(request.bpmnXml()));
        String resourceName = request.processDefinitionKey() + ".bpmn20.xml";
        Deployment deployment = repositoryService.createDeployment()
                .name(request.processDefinitionName())
                .key(request.processDefinitionKey())
                .tenantId(tenant.tenantId())
                .addString(resourceName, request.bpmnXml())
                .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionTenantId(tenant.tenantId())
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "No process definition was deployed");
        }
        if (!request.processDefinitionKey().equals(definition.getKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "BPMN process id must equal processDefinitionKey");
        }
        auditService.record(
                "PROCESS_DEFINITION_DEPLOYED", null, definition.getKey(), null,
                "version=" + definition.getVersion(), null, "DEPLOYED", null);
        return new DeployProcessResult(
                deployment.getId(),
                definition.getId(),
                definition.getKey(),
                definition.getName(),
                definition.getVersion()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActiveProcessVersionResult activate(ActivateProcessVersionRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenant.tenantId())
                .processDefinitionKey(request.processDefinitionKey())
                .processDefinitionVersion(request.version())
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition version does not exist");
        }
        CurrentPrincipal principal = principalProvider.current();
        String activatedBy = StringUtils.hasText(principal.username())
                ? principal.username()
                : principal.principalId();
        jdbcTemplate.update("""
                INSERT INTO workflow_active_version
                    (tenant_id, process_definition_key, process_definition_id, version, activated_by, activated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, process_definition_key)
                DO UPDATE SET
                    process_definition_id = EXCLUDED.process_definition_id,
                    version = EXCLUDED.version,
                    activated_by = EXCLUDED.activated_by,
                    activated_at = CURRENT_TIMESTAMP
                """, tenant.tenantId(), definition.getKey(), definition.getId(), definition.getVersion(), activatedBy);
        auditService.record(
                "PROCESS_DEFINITION_ACTIVATED", null, definition.getKey(), null,
                "version=" + definition.getVersion(), "DEPLOYED", "ACTIVE", null);
        return getActiveVersion(request.processDefinitionKey());
    }

    @Override
    public ActiveProcessVersionResult getActiveVersion(String processDefinitionKey) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        List<ActiveProcessVersionResult> results = jdbcTemplate.query("""
                SELECT tenant_id, process_definition_key, process_definition_id, version, activated_by, activated_at
                FROM workflow_active_version
                WHERE tenant_id = ? AND process_definition_key = ?
                """, this::mapActiveVersion, tenant.tenantId(), processDefinitionKey);
        if (results.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Active process definition version does not exist");
        }
        return results.getFirst();
    }

    @Override
    public boolean exists(String processDefinitionKey) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenant.tenantId())
                .processDefinitionKey(processDefinitionKey)
                .count() > 0;
    }

    @Override
    public List<ProcessDefinitionView> list(String processDefinitionKey) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        var query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenant.tenantId())
                .orderByProcessDefinitionKey()
                .asc()
                .orderByProcessDefinitionVersion()
                .desc();
        if (StringUtils.hasText(processDefinitionKey)) {
            query.processDefinitionKey(processDefinitionKey);
        } else {
            query.latestVersion();
        }
        return query.list().stream()
                .map(definition -> toView(definition, false))
                .toList();
    }

    @Override
    public ProcessDefinitionView getDefinition(String processDefinitionKey, Integer version) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        var query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenant.tenantId())
                .processDefinitionKey(processDefinitionKey);
        ProcessDefinition definition = version == null
                ? query.latestVersion().singleResult()
                : query.processDefinitionVersion(version).singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return toView(definition, true);
    }

    private ProcessDefinitionView toView(ProcessDefinition definition, boolean includeXml) {
        boolean active = isActive(definition);
        return new ProcessDefinitionView(
                definition.getId(),
                definition.getKey(),
                definition.getName(),
                definition.getVersion(),
                definition.getDeploymentId(),
                getDeploymentTime(definition.getDeploymentId()),
                definition.getTenantId(),
                active,
                includeXml ? readBpmnXml(definition) : null
        );
    }

    private OffsetDateTime getDeploymentTime(String deploymentId) {
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(deploymentId)
                .singleResult();
        if (deployment == null || deployment.getDeploymentTime() == null) {
            return null;
        }
        return deployment.getDeploymentTime().toInstant().atOffset(ZoneOffset.UTC);
    }

    private boolean isActive(ProcessDefinition definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM workflow_active_version
                WHERE tenant_id = ? AND process_definition_key = ? AND process_definition_id = ?
                """, Integer.class, definition.getTenantId(), definition.getKey(), definition.getId());
        return count != null && count > 0;
    }

    private String readBpmnXml(ProcessDefinition definition) {
        try (InputStream inputStream = repositoryService.getResourceAsStream(
                definition.getDeploymentId(), definition.getResourceName())) {
            if (inputStream == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "BPMN XML resource does not exist");
            }
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Failed to read BPMN XML");
        }
    }

    private ActiveProcessVersionResult mapActiveVersion(ResultSet resultSet, int rowNum) throws SQLException {
        return new ActiveProcessVersionResult(
                resultSet.getString("tenant_id"),
                resultSet.getString("process_definition_key"),
                resultSet.getString("process_definition_id"),
                resultSet.getInt("version"),
                resultSet.getString("activated_by"),
                resultSet.getObject("activated_at", OffsetDateTime.class)
        );
    }
}

package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionDiagramView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class WorkflowDefinitionReadService {

    private final RepositoryService repositoryService;
    private final JdbcTemplate jdbcTemplate;
    private final TenantProvider tenantProvider;

    WorkflowDefinitionReadService(RepositoryService repositoryService,
                                  JdbcTemplate jdbcTemplate,
                                  TenantProvider tenantProvider) {
        this.repositoryService = repositoryService;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantProvider = tenantProvider;
    }

    PageResult<ProcessDefinitionSummaryView> page(
            Integer pageNum, Integer pageSize, String key, String name, String publishStatus) {
        int normalizedPage = normalizePage(pageNum);
        int normalizedSize = normalizeSize(pageSize);
        String tenantId = tenantProvider.current().tenantId();
        var query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .latestVersion();
        if (StringUtils.hasText(key)) {
            query.processDefinitionKeyLike("%" + key.trim() + "%");
        }
        if (StringUtils.hasText(name)) {
            query.processDefinitionNameLike("%" + name.trim() + "%");
        }
        Map<String, ActiveVersionRow> activeVersions = activeVersions(tenantId);
        List<ProcessDefinition> definitions = query.list();
        Map<String, ProcessDefinition> activeDefinitions = definitionsById(activeVersions.values().stream()
                .map(ActiveVersionRow::processDefinitionId)
                .collect(Collectors.toSet()));
        Map<String, OffsetDateTime> deploymentTimes = deploymentTimes(definitions.stream()
                .map(ProcessDefinition::getDeploymentId)
                .collect(Collectors.toSet()));
        String normalizedStatus = normalizeStatus(publishStatus);
        List<ProcessDefinitionSummaryView> summaries = definitions.stream()
                .map(definition -> toSummary(definition, activeVersions.get(definition.getKey()),
                        activeDefinitions, deploymentTimes))
                .filter(summary -> "all".equals(normalizedStatus)
                        || normalizedStatus.equals(summary.publishStatus()))
                .sorted(Comparator.comparing(
                        ProcessDefinitionSummaryView::latestDeployTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return slice(summaries, normalizedPage, normalizedSize);
    }

    ProcessDefinitionDiagramView diagram(String key, Integer version, String processDefinitionId) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition definition = resolve(tenantId, key, version, processDefinitionId);
        ActiveVersionRow activeVersion = activeVersions(tenantId).get(definition.getKey());
        return new ProcessDefinitionDiagramView(
                definition.getId(), definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getDeploymentId(), deploymentTime(definition.getDeploymentId()),
                activeVersion != null && definition.getId().equals(activeVersion.processDefinitionId()),
                readBpmnXml(definition), definition.getTenantId());
    }

    String readBpmnXml(String processDefinitionId) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return readBpmnXml(definition);
    }

    private ProcessDefinitionSummaryView toSummary(
            ProcessDefinition definition,
            ActiveVersionRow active,
            Map<String, ProcessDefinition> activeDefinitions,
            Map<String, OffsetDateTime> deploymentTimes) {
        ProcessDefinition activeDefinition = active == null ? null : activeDefinitions.get(active.processDefinitionId());
        return new ProcessDefinitionSummaryView(
                definition.getId(), definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getDeploymentId(), deploymentTimes.get(definition.getDeploymentId()),
                active == null ? null : active.version(), active == null ? null : active.processDefinitionId(),
                activeDefinition == null ? null : activeDefinition.getDeploymentId(),
                active == null ? null : active.activatedAt(),
                active == null ? "unpublished" : "published", definition.getTenantId());
    }

    private Map<String, ProcessDefinition> definitionsById(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repositoryService.createProcessDefinitionQuery().processDefinitionIds(ids).list().stream()
                .collect(Collectors.toMap(ProcessDefinition::getId, Function.identity()));
    }

    private Map<String, OffsetDateTime> deploymentTimes(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repositoryService.createDeploymentQuery().deploymentIds(List.copyOf(ids)).list().stream()
                .collect(Collectors.toMap(Deployment::getId,
                        deployment -> toOffsetDateTime(deployment.getDeploymentTime())));
    }

    private ProcessDefinition resolve(String tenantId, String key, Integer version, String definitionId) {
        ProcessDefinition definition;
        if (StringUtils.hasText(definitionId)) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(tenantId)
                    .processDefinitionId(definitionId.trim())
                    .singleResult();
            if (definition != null && StringUtils.hasText(key) && !key.trim().equals(definition.getKey())) {
                definition = null;
            }
        } else if (version != null) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(tenantId)
                    .processDefinitionKey(key)
                    .processDefinitionVersion(version)
                    .singleResult();
        } else {
            ActiveVersionRow active = activeVersions(tenantId).get(key);
            definition = active == null ? null : repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(tenantId)
                    .processDefinitionId(active.processDefinitionId())
                    .singleResult();
        }
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return definition;
    }

    private Map<String, ActiveVersionRow> activeVersions(String tenantId) {
        return jdbcTemplate.query("""
                SELECT process_definition_key, process_definition_id, version, activated_at
                FROM workflow_active_version WHERE tenant_id = ?
                """, (resultSet, rowNumber) -> new ActiveVersionRow(
                resultSet.getString("process_definition_key"),
                resultSet.getString("process_definition_id"),
                resultSet.getInt("version"),
                resultSet.getObject("activated_at", OffsetDateTime.class)), tenantId)
                .stream()
                .collect(Collectors.toMap(ActiveVersionRow::processDefinitionKey, Function.identity()));
    }

    private String readBpmnXml(ProcessDefinition definition) {
        try (InputStream input = repositoryService.getResourceAsStream(
                definition.getDeploymentId(), definition.getResourceName())) {
            if (input == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "BPMN XML resource does not exist");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "BPMN XML cannot be read");
        }
    }

    private OffsetDateTime deploymentTime(String deploymentId) {
        Deployment deployment = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult();
        return deployment == null ? null : toOffsetDateTime(deployment.getDeploymentTime());
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "all";
        if (!Set.of("all", "published", "unpublished").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported status: " + status);
        }
        return normalized;
    }

    private int normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
    }

    private <T> PageResult<T> slice(List<T> source, int pageNum, int pageSize) {
        int fromIndex = Math.min((pageNum - 1) * pageSize, source.size());
        int toIndex = Math.min(fromIndex + pageSize, source.size());
        return new PageResult<>(source.size(), pageNum, pageSize, source.subList(fromIndex, toIndex));
    }

    private record ActiveVersionRow(
            String processDefinitionKey,
            String processDefinitionId,
            int version,
            OffsetDateTime activatedAt) {
    }
}

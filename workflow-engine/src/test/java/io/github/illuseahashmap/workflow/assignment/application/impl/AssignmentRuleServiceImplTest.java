package io.github.illuseahashmap.workflow.assignment.application.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.rules.RuleEngine;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.assignment.application.port.ProcessDefinitionCatalog;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTarget;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRuleRepository;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssignmentRuleServiceImplTest {

    private static final String TENANT_ID = "tenant-a";

    @Mock
    private NodeAssignmentRuleRepository ruleRepository;

    @Mock
    private ProcessDefinitionCatalog definitionCatalog;

    @Mock
    private RuleEngine ruleEngine;

    private AssignmentRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantProvider tenantProvider = () -> new TenantContext.TenantInfo(TENANT_ID, "tenant-a", "Tenant A");
        service = new AssignmentRuleServiceImpl(ruleRepository, definitionCatalog, ruleEngine, tenantProvider);
    }

    @Test
    void shouldAllowUpdatingRuleContentForHistoricalProcessVersion() {
        NodeAssignmentRule existing = rule(10L, "expense:1:100", 1, "approve", "old description");
        AssignmentRuleCommand command = command("expense:1:100", "approve", "new description");
        when(ruleRepository.findById(TENANT_ID, 10L)).thenReturn(Optional.of(existing));
        when(definitionCatalog.findById(TENANT_ID, "expense:1:100"))
                .thenReturn(Optional.of(new ProcessDefinitionCatalog.DefinitionInfo("expense:1:100", "expense", 1)));
        when(definitionCatalog.expectedAssignmentType("expense:1:100", "approve"))
                .thenReturn(AssignmentType.ASSIGNEE);

        service.update(10L, command);

        ArgumentCaptor<NodeAssignmentRule> captor = ArgumentCaptor.forClass(NodeAssignmentRule.class);
        verify(ruleRepository).update(captor.capture());
        assertEquals("expense:1:100", captor.getValue().processDefinitionId());
        assertEquals(1, captor.getValue().version());
        assertEquals("new description", captor.getValue().description());
    }

    @Test
    void shouldRejectMovingRuleToAnotherProcessVersion() {
        NodeAssignmentRule existing = rule(10L, "expense:1:100", 1, "approve", null);
        when(ruleRepository.findById(TENANT_ID, 10L)).thenReturn(Optional.of(existing));

        assertThrows(BusinessException.class,
                () -> service.update(10L, command("expense:2:200", "approve", null)));

        verify(ruleRepository, never()).update(any());
        verify(definitionCatalog, never()).findById(any(), any());
    }

    @Test
    void shouldTreatCandidateAssignmentVariantsAsTheSameUserTaskMode() {
        AssignmentRuleCommand command = new AssignmentRuleCommand(
                "expense:1:100",
                "approve",
                100,
                AssignmentType.CANDIDATE_USERS,
                List.of(),
                List.of("reviewer"),
                List.of(),
                List.of(),
                EmptyUserStrategy.AUTO_COMPLETE,
                null,
                true,
                null,
                List.of());
        when(definitionCatalog.findById(TENANT_ID, "expense:1:100"))
                .thenReturn(Optional.of(new ProcessDefinitionCatalog.DefinitionInfo("expense:1:100", "expense", 1)));
        when(definitionCatalog.expectedAssignmentType("expense:1:100", "approve"))
                .thenReturn(AssignmentType.MIXED);
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(command);

        ArgumentCaptor<NodeAssignmentRule> captor = ArgumentCaptor.forClass(NodeAssignmentRule.class);
        verify(ruleRepository).save(captor.capture());
        assertEquals(AssignmentType.CANDIDATE_USERS, captor.getValue().assignmentType());
        assertEquals(List.of("reviewer"), captor.getValue().targetValues(AssignmentTargetType.CANDIDATE_USER));
    }

    @Test
    void shouldInheritOnlyTasksNotConfiguredOnTargetVersion() {
        ProcessDefinitionCatalog.DefinitionInfo target =
                new ProcessDefinitionCatalog.DefinitionInfo("expense:2:200", "expense", 2);
        ProcessDefinitionCatalog.DefinitionInfo source =
                new ProcessDefinitionCatalog.DefinitionInfo("expense:1:100", "expense", 1);
        NodeAssignmentRule configured = rule(20L, target.id(), 2, "approve", null);
        NodeAssignmentRule sourceApprove = rule(10L, source.id(), 1, "approve", null);
        NodeAssignmentRule sourceArchive = rule(11L, source.id(), 1, "archive", null);
        when(definitionCatalog.findById(TENANT_ID, target.id())).thenReturn(Optional.of(target));
        when(definitionCatalog.findVersions(TENANT_ID, "expense")).thenReturn(List.of(target, source));
        when(ruleRepository.count(TENANT_ID, source.id())).thenReturn(2L);
        when(ruleRepository.findByProcessDefinition(TENANT_ID, target.id())).thenReturn(List.of(configured));
        when(ruleRepository.findByProcessDefinition(TENANT_ID, source.id()))
                .thenReturn(List.of(sourceApprove, sourceArchive));
        when(definitionCatalog.expectedAssignmentType(target.id(), "archive"))
                .thenReturn(AssignmentType.ASSIGNEE);
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssignmentRuleInheritResult result = service.inherit(target.id());

        assertEquals(1, result.copiedCount());
        assertEquals(1, result.skippedCount());
        verify(ruleRepository).save(any());
    }

    private AssignmentRuleCommand command(String processDefinitionId, String taskDefinitionKey, String description) {
        return new AssignmentRuleCommand(
                processDefinitionId,
                taskDefinitionKey,
                100,
                AssignmentType.ASSIGNEE,
                List.of("operator"),
                List.of(),
                List.of(),
                List.of(),
                EmptyUserStrategy.TO_ASSIGNEE,
                "fallback",
                true,
                description,
                List.of());
    }

    private NodeAssignmentRule rule(Long id, String processDefinitionId, int version,
                                    String taskDefinitionKey, String description) {
        return new NodeAssignmentRule(
                id,
                TENANT_ID,
                processDefinitionId,
                "expense",
                version,
                taskDefinitionKey,
                100,
                AssignmentType.ASSIGNEE,
                EmptyUserStrategy.TO_ASSIGNEE,
                true,
                description,
                List.of(),
                List.of(
                        new AssignmentTarget(null, AssignmentTargetType.ASSIGNEE, "operator", 10),
                        new AssignmentTarget(null, AssignmentTargetType.FALLBACK_ASSIGNEE, "fallback", 20)),
                null,
                null);
    }
}

package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinition;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentManualRunCommand;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRunSubmissionServiceImplTest {

    private final AgentDefinitionRepository definitionRepository = mock(AgentDefinitionRepository.class);
    private final AgentDefinitionVersionRepository versionRepository =
            mock(AgentDefinitionVersionRepository.class);
    private final AgentProviderRepository providerRepository = mock(AgentProviderRepository.class);
    private final AgentRunExecutionRepository executionRepository = mock(AgentRunExecutionRepository.class);
    private final TenantProvider tenantProvider = mock(TenantProvider.class);
    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final AgentRunSubmissionServiceImpl service = new AgentRunSubmissionServiceImpl(
            definitionRepository,
            versionRepository,
            providerRepository,
            executionRepository,
            tenantProvider,
            principalProvider,
            new ObjectMapper());

    @BeforeEach
    void setUpContext() {
        when(tenantProvider.current()).thenReturn(new TenantContext.TenantInfo("tenant-id", "tenant-a", "A"));
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "user-1", "admin", "Admin", "tenant-a", Set.of(), Set.of()));
    }

    @Test
    void submitsPublishedVersionAsStandardQueuedRun() {
        OffsetDateTime now = OffsetDateTime.now();
        AgentDefinition definition = new AgentDefinition(
                1L, "tenant-a", "review", "Review", null, true, now, now);
        AgentDefinitionVersion version = version(now);
        when(definitionRepository.findById("tenant-a", 1L)).thenReturn(Optional.of(definition));
        when(versionRepository.findPublished("tenant-a", 1L)).thenReturn(Optional.of(version));
        when(providerRepository.findById("tenant-a", 30L)).thenReturn(Optional.of(provider(now)));
        when(executionRepository.insertQueued(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            AgentRunExecutionRepository.Submission submission = invocation.getArgument(0);
            return AgentRun.queued(
                    99L,
                    submission.tenantCode(),
                    submission.agentVersionId(),
                    submission.idempotencyKey(),
                    submission.deadlineAt(),
                    submission.createdAt());
        });

        var result = service.submitManual(new AgentManualRunCommand(1L, "  review this  "));

        assertThat(result.runId()).isEqualTo(99L);
        ArgumentCaptor<AgentRunExecutionRepository.Submission> captor =
                ArgumentCaptor.forClass(AgentRunExecutionRepository.Submission.class);
        verify(executionRepository).insertQueued(captor.capture());
        assertThat(captor.getValue().inputSnapshotJson()).contains("review this");
        assertThat(captor.getValue().requestedBy()).isEqualTo("user-1");
    }

    @Test
    void rejectsAgentWithoutPublishedVersion() {
        OffsetDateTime now = OffsetDateTime.now();
        when(definitionRepository.findById("tenant-a", 1L)).thenReturn(Optional.of(new AgentDefinition(
                1L, "tenant-a", "review", "Review", null, true, now, now)));
        when(versionRepository.findPublished("tenant-a", 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitManual(new AgentManualRunCommand(1L, "input")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Publish an Agent version before starting a test run");
    }

    private AgentDefinitionVersion version(OffsetDateTime now) {
        return new AgentDefinitionVersion(
                20L,
                "tenant-a",
                1L,
                1,
                AgentVersionStatus.PUBLISHED,
                30L,
                "model",
                "prompt",
                120,
                AgentFailurePolicy.FAIL_PROCESS,
                null,
                "admin",
                "admin",
                now,
                now,
                now);
    }

    private AgentProvider provider(OffsetDateTime now) {
        return new AgentProvider(
                30L,
                "tenant-a",
                "provider",
                "Provider",
                AgentProviderType.MOCK,
                null,
                "model",
                true,
                false,
                null,
                now,
                now);
    }
}

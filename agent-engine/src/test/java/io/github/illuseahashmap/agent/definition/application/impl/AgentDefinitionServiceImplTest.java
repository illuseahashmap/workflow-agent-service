package io.github.illuseahashmap.agent.definition.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinition;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentDefinitionServiceImplTest {

    private final AgentDefinitionRepository definitionRepository = mock(AgentDefinitionRepository.class);
    private final AgentDefinitionVersionRepository versionRepository = mock(AgentDefinitionVersionRepository.class);
    private final AgentProviderRepository providerRepository = mock(AgentProviderRepository.class);
    private final TenantProvider tenantProvider = mock(TenantProvider.class);
    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final AgentDefinitionServiceImpl service = new AgentDefinitionServiceImpl(
            definitionRepository, versionRepository, providerRepository, tenantProvider,
            principalProvider, new ObjectMapper());

    @BeforeEach
    void setUpContext() {
        when(tenantProvider.current()).thenReturn(new TenantContext.TenantInfo(
                "tenant-id", "tenant-a", "Tenant A"));
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "user-1", "admin", "Admin", "tenant-a",
                Set.of("TENANT_ADMIN"), Set.of("agent:manage")));
    }

    @Test
    void creatingDefinitionAlsoCreatesInitialDraft() {
        AgentDefinition saved = definition(true);
        when(definitionRepository.save(any())).thenReturn(saved);
        when(versionRepository.save(any()))
                .thenAnswer(invocation -> invocation.<AgentDefinitionVersion>getArgument(0));
        when(versionRepository.findByDefinition("tenant-a", 100L)).thenReturn(List.of(draft()));

        var view = service.create(new AgentDefinitionCommand(
                "expense_review", "Expense Review", "Review expenses", true));

        assertThat(view.latestVersion()).isEqualTo(1);
        verify(versionRepository).save(any(AgentDefinitionVersion.class));
    }

    @Test
    void publishedVersionCannotBeEdited() {
        when(definitionRepository.findById("tenant-a", 100L)).thenReturn(Optional.of(definition(true)));
        when(versionRepository.findById("tenant-a", 100L, 200L))
                .thenReturn(Optional.of(publishedVersion()));

        assertThatThrownBy(() -> service.updateDraft(100L, 200L, versionCommand()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Published Agent versions are immutable");

        verify(versionRepository, never()).updateDraft(any());
    }

    @Test
    void publishingOpenAiVersionRequiresEncryptedCredential() {
        AgentDefinitionVersion draft = draftWithProvider();
        AgentProvider provider = new AgentProvider(
                300L, "tenant-a", "openai", "OpenAI", AgentProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", "model-a", true, false, null, null, null);
        when(definitionRepository.findById("tenant-a", 100L)).thenReturn(Optional.of(definition(true)));
        when(versionRepository.findById("tenant-a", 100L, 200L)).thenReturn(Optional.of(draft));
        when(providerRepository.findById("tenant-a", 300L)).thenReturn(Optional.of(provider));

        assertThatThrownBy(() -> service.publish(100L, 200L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Provider credential is required before publishing");

        verify(versionRepository, never()).publish(any(), anyLong(), anyLong(), any());
    }

    @Test
    void publishingMockVersionLocksDraft() {
        AgentDefinitionVersion draft = draftWithProvider();
        AgentDefinitionVersion published = publishedVersion();
        AgentProvider provider = new AgentProvider(
                300L, "tenant-a", "mock", "Mock", AgentProviderType.MOCK,
                null, null, true, false, null, null, null);
        when(definitionRepository.findById("tenant-a", 100L)).thenReturn(Optional.of(definition(true)));
        when(versionRepository.findById("tenant-a", 100L, 200L))
                .thenReturn(Optional.of(draft))
                .thenReturn(Optional.of(published));
        when(providerRepository.findById("tenant-a", 300L)).thenReturn(Optional.of(provider));

        var view = service.publish(100L, 200L);

        verify(versionRepository).publish("tenant-a", 100L, 200L, "user-1");
        assertThat(view.status()).isEqualTo(AgentVersionStatus.PUBLISHED);
    }

    @Test
    void outputSchemaMustBeValidJson() {
        when(definitionRepository.findById("tenant-a", 100L)).thenReturn(Optional.of(definition(true)));
        when(versionRepository.findById("tenant-a", 100L, 200L)).thenReturn(Optional.of(draft()));

        AgentVersionCommand invalid = new AgentVersionCommand(
                null, null, "prompt", 120, AgentFailurePolicy.FAIL_PROCESS, "{invalid");

        assertThatThrownBy(() -> service.updateDraft(100L, 200L, invalid))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Output Schema must be valid JSON");
    }

    private AgentDefinition definition(boolean enabled) {
        return new AgentDefinition(
                100L, "tenant-a", "expense_review", "Expense Review",
                "Review expenses", enabled, null, null);
    }

    private AgentDefinitionVersion draft() {
        return new AgentDefinitionVersion(
                200L, "tenant-a", 100L, 1, AgentVersionStatus.DRAFT,
                null, null, "", 120, AgentFailurePolicy.FAIL_PROCESS,
                null, "user-1", null, null, null, null);
    }

    private AgentDefinitionVersion draftWithProvider() {
        return new AgentDefinitionVersion(
                200L, "tenant-a", 100L, 1, AgentVersionStatus.DRAFT,
                300L, "model-a", "You review expenses.", 120, AgentFailurePolicy.FAIL_PROCESS,
                null, "user-1", null, null, null, null);
    }

    private AgentDefinitionVersion publishedVersion() {
        AgentDefinitionVersion draft = draftWithProvider();
        return new AgentDefinitionVersion(
                draft.id(), draft.tenantCode(), draft.definitionId(), draft.version(), AgentVersionStatus.PUBLISHED,
                draft.providerId(), draft.modelName(), draft.systemPrompt(), draft.timeoutSeconds(),
                draft.failurePolicy(), draft.outputSchema(), draft.createdBy(), "user-1", null, null, null);
    }

    private AgentVersionCommand versionCommand() {
        return new AgentVersionCommand(
                300L, "model-a", "You review expenses.", 120,
                AgentFailurePolicy.FAIL_PROCESS, "{\"type\":\"object\"}");
    }
}

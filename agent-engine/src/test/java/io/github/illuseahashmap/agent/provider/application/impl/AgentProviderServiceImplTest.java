package io.github.illuseahashmap.agent.provider.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderCommand;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialCipher;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentProviderServiceImplTest {

    private final AgentProviderRepository repository = mock(AgentProviderRepository.class);
    private final AgentCredentialCipher credentialCipher = mock(AgentCredentialCipher.class);
    private final TenantProvider tenantProvider = mock(TenantProvider.class);
    private final AgentProviderServiceImpl service = new AgentProviderServiceImpl(
            repository, credentialCipher, tenantProvider);

    @BeforeEach
    void setUpTenant() {
        when(tenantProvider.current()).thenReturn(new TenantContext.TenantInfo(
                "tenant-id", "tenant-a", "Tenant A"));
    }

    @Test
    void createsMockProviderWithoutCredential() {
        AgentProvider saved = provider(10L, AgentProviderType.MOCK, false, null);
        when(repository.save(any())).thenReturn(saved);
        when(repository.findById("tenant-a", 10L)).thenReturn(Optional.of(saved));

        var view = service.create(new AgentProviderCommand(
                "mock_local", "Local Mock", AgentProviderType.MOCK, null, null, null, true));

        assertThat(view.type()).isEqualTo(AgentProviderType.MOCK);
        verify(credentialCipher, never()).encrypt(any(), anyLong(), any());
        verify(repository, never()).saveCredential(any(), anyLong(), any(), any());
    }

    @Test
    void encryptsOpenAiCredentialAndStoresOnlyHint() {
        AgentProvider saved = provider(11L, AgentProviderType.OPENAI_COMPATIBLE, false, null);
        AgentProvider configured = provider(11L, AgentProviderType.OPENAI_COMPATIBLE, true, "3456");
        when(repository.save(any())).thenReturn(saved);
        when(repository.findById("tenant-a", 11L))
                .thenReturn(Optional.of(configured));
        when(credentialCipher.encrypt("tenant-a", 11L, "secret-123456")).thenReturn("encrypted-value");

        var view = service.create(new AgentProviderCommand(
                "openai", "OpenAI", AgentProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1", "model-a", "secret-123456", true));

        verify(repository).saveCredential("tenant-a", 11L, "encrypted-value", "3456");
        assertThat(view.credentialConfigured()).isTrue();
        assertThat(view.credentialHint()).isEqualTo("3456");
    }

    @Test
    void rejectsInvalidOpenAiBaseUrlBeforeSaving() {
        assertThatThrownBy(() -> service.create(new AgentProviderCommand(
                "openai", "OpenAI", AgentProviderType.OPENAI_COMPATIBLE,
                "file:///etc/passwd", "model-a", null, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Provider Base URL must be a valid HTTP URL");

        verify(repository, never()).save(any());
    }

    @Test
    void updateKeepsCredentialWhenSecretIsBlank() {
        AgentProvider existing = provider(12L, AgentProviderType.OPENAI_COMPATIBLE, true, "3456");
        when(repository.findById("tenant-a", 12L)).thenReturn(Optional.of(existing));

        service.update(12L, new AgentProviderCommand(
                "openai", "Updated", AgentProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", "model-b", " ", true));

        ArgumentCaptor<AgentProvider> providerCaptor = ArgumentCaptor.forClass(AgentProvider.class);
        verify(repository).update(providerCaptor.capture());
        assertThat(providerCaptor.getValue().credentialConfigured()).isTrue();
        verify(repository, never()).saveCredential(any(), anyLong(), any(), any());
    }

    private AgentProvider provider(
            long id,
            AgentProviderType type,
            boolean credentialConfigured,
            String credentialHint
    ) {
        return new AgentProvider(
                id, "tenant-a", type == AgentProviderType.MOCK ? "mock_local" : "openai",
                "Provider", type, type == AgentProviderType.MOCK ? null : "https://api.example.com/v1",
                "model-a", true, credentialConfigured, credentialHint, null, null);
    }
}

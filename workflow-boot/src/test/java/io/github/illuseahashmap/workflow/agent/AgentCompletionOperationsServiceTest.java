package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.domain.SecurityAuditRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentCompletionOperationsServiceTest {

    private final AgentCompletionEventStore eventStore = mock(AgentCompletionEventStore.class);
    private final SecurityAuditRepository auditRepository = mock(SecurityAuditRepository.class);
    private final CurrentPrincipalProvider principalProvider = () -> new CurrentPrincipal(
            "USER", "admin-a", "admin", "Administrator", "platform",
            Set.of("PLATFORM_ADMIN"), Set.of());
    private final AgentCompletionOperationsService service = new AgentCompletionOperationsService(
            eventStore, auditRepository, principalProvider);

    @Test
    void ignoreRequiresReasonAndAuditsTargetBusinessContext() {
        UUID eventId = UUID.randomUUID();
        var event = event(eventId);
        when(eventStore.ignore(eventId, "Business repaired", "admin-a")).thenReturn(event);

        service.ignore(eventId, " Business repaired ");

        verify(auditRepository).record(
                eq("AGENT_COMPLETION_IGNORED"), eq("admin-a"), eq("tenant-a"), eq(null),
                eq(eventId.toString()), eq("SUCCESS"),
                contains("processInstance=process-a"));
    }

    @Test
    void replayRejectsEventThatIsNoLongerDeadLettered() {
        UUID eventId = UUID.randomUUID();
        when(eventStore.replay(eventId)).thenReturn(null);

        assertThatThrownBy(() -> service.replay(eventId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void listsAndReplaysDeadLettersWithAuditEvidence() {
        UUID eventId = UUID.randomUUID();
        var event = event(eventId);
        when(eventStore.deadLetters(20)).thenReturn(List.of(event));
        when(eventStore.replay(eventId)).thenReturn(event);

        org.assertj.core.api.Assertions.assertThat(service.deadLetters(20)).containsExactly(event);
        service.replay(eventId);

        verify(auditRepository).record(
                eq("AGENT_COMPLETION_REPLAYED"), eq("admin-a"), eq("tenant-a"), eq(null),
                eq(eventId.toString()), eq("SUCCESS"), contains("method=REPLAY"));
    }

    private AgentCompletionEventStore.DeadLetterEvent event(UUID eventId) {
        return new AgentCompletionEventStore.DeadLetterEvent(
                eventId, "tenant-a", "42", 3, "mapping failed",
                OffsetDateTime.now(), OffsetDateTime.now(), "process-a", "execution-a");
    }
}

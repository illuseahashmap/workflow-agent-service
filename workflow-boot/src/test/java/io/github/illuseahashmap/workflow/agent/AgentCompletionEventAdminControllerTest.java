package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentCompletionEventAdminControllerTest {

    private final AgentCompletionOperationsService operationsService =
            mock(AgentCompletionOperationsService.class);
    private final AgentCompletionEventAdminController controller =
            new AgentCompletionEventAdminController(operationsService);

    @Test
    void delegatesRestrictedOperationsToApplicationService() {
        when(operationsService.deadLetters(25)).thenReturn(List.of());
        UUID eventId = UUID.randomUUID();

        assertThat(controller.deadLetters(25).data()).isEmpty();
        assertThat(controller.replay(eventId).data()).isNull();
        assertThat(controller.ignore(eventId,
                new AgentCompletionEventAdminController.IgnoreCompletionEventCommand("resolved")).data()).isNull();

        verify(operationsService).deadLetters(25);
        verify(operationsService).replay(eventId);
        verify(operationsService).ignore(eventId, "resolved");
    }
}

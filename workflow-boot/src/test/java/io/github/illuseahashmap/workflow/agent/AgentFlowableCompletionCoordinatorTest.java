package io.github.illuseahashmap.workflow.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.AgentCompletionContractException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AgentFlowableCompletionCoordinatorTest {

    @Test
    void poisonEventIsDeadLetteredWithoutBlockingFollowingEvent() {
        AgentCompletionEventStore eventStore = mock(AgentCompletionEventStore.class);
        AgentFlowableCompletionProcessor processor = mock(AgentFlowableCompletionProcessor.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        UUID poison = UUID.randomUUID();
        UUID valid = UUID.randomUUID();
        when(eventStore.claim(anyString(), eq(20), eq(Duration.ofSeconds(60))))
                .thenReturn(List.of(poison, valid));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        doAnswer(invocation -> {
            if (poison.equals(invocation.getArgument(0))) {
                throw new AgentCompletionContractException("bad payload");
            }
            return null;
        }).when(processor).process(any(), anyString());
        TaskExecutor directExecutor = Runnable::run;
        var coordinator = new AgentFlowableCompletionCoordinator(
                eventStore, processor, transactionTemplate, directExecutor,
                "test", 8, 20, 60);

        coordinator.resumeCompletedRuns();

        verify(eventStore).retryOrDeadLetter(
                eq(poison), anyString(), any(AgentCompletionContractException.class), eq(8), eq(true));
        verify(processor).process(eq(valid), anyString());
    }
}

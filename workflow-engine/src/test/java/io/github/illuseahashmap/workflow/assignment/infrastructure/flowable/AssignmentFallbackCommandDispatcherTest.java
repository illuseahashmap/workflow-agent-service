package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackAction;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackCommand;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackCommandRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssignmentFallbackCommandDispatcherTest {

    @Mock
    private AssignmentFallbackCommandRepository commandRepository;

    @Mock
    private AssignmentFallbackExecutor fallbackExecutor;

    @Test
    void shouldExecuteAndCompleteAutoCompleteCommand() {
        AssignmentFallbackCommand command = command(AssignmentFallbackAction.AUTO_COMPLETE, 1);
        when(commandRepository.claimNext(Duration.ofMinutes(5))).thenReturn(command).thenReturn(null);

        new AssignmentFallbackCommandDispatcher(commandRepository, fallbackExecutor).dispatchPendingCommands();

        verify(fallbackExecutor).autoComplete("task-1", "instance-1");
        verify(commandRepository).markSucceeded(1L);
    }

    @Test
    void shouldExecuteAndCompleteAutoRejectCommand() {
        AssignmentFallbackCommand command = command(AssignmentFallbackAction.AUTO_REJECT, 1);
        when(commandRepository.claimNext(Duration.ofMinutes(5))).thenReturn(command).thenReturn(null);

        new AssignmentFallbackCommandDispatcher(commandRepository, fallbackExecutor).dispatchPendingCommands();

        verify(fallbackExecutor).autoReject("task-1", "instance-1");
        verify(commandRepository).markSucceeded(1L);
    }

    @Test
    void shouldRescheduleTransientFailure() {
        AssignmentFallbackCommand command = command(AssignmentFallbackAction.AUTO_COMPLETE, 2);
        when(commandRepository.claimNext(Duration.ofMinutes(5))).thenReturn(command).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("lock busy"))
                .when(fallbackExecutor).autoComplete("task-1", "instance-1");

        new AssignmentFallbackCommandDispatcher(commandRepository, fallbackExecutor).dispatchPendingCommands();

        verify(commandRepository).reschedule(1L, Duration.ofSeconds(2), "IllegalStateException: lock busy");
    }

    @Test
    void shouldStopRetryingAfterMaximumAttempts() {
        AssignmentFallbackCommand command = command(AssignmentFallbackAction.AUTO_COMPLETE, 10);
        when(commandRepository.claimNext(Duration.ofMinutes(5))).thenReturn(command).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("permanent"))
                .when(fallbackExecutor).autoComplete("task-1", "instance-1");

        new AssignmentFallbackCommandDispatcher(commandRepository, fallbackExecutor).dispatchPendingCommands();

        verify(commandRepository).markFailed(1L, "IllegalStateException: permanent");
    }

    private AssignmentFallbackCommand command(AssignmentFallbackAction action, int attempts) {
        return new AssignmentFallbackCommand(1L, "tenant-1", "task-1", "instance-1", action, attempts);
    }
}

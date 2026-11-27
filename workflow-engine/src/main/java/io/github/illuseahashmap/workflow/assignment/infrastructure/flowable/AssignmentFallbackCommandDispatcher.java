package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackCommand;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackCommandRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AssignmentFallbackCommandDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssignmentFallbackCommandDispatcher.class);
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 10;
    private static final int BATCH_SIZE = 20;

    private final AssignmentFallbackCommandRepository commandRepository;
    private final AssignmentFallbackExecutor fallbackExecutor;

    public AssignmentFallbackCommandDispatcher(AssignmentFallbackCommandRepository commandRepository,
                                               AssignmentFallbackExecutor fallbackExecutor) {
        this.commandRepository = commandRepository;
        this.fallbackExecutor = fallbackExecutor;
    }

    @Scheduled(fixedDelayString = "${workflow.assignment.fallback-dispatch-interval-ms:1000}")
    public void dispatchPendingCommands() {
        for (int index = 0; index < BATCH_SIZE; index++) {
            AssignmentFallbackCommand command = commandRepository.claimNext(PROCESSING_TIMEOUT);
            if (command == null) {
                return;
            }
            execute(command);
        }
    }

    private void execute(AssignmentFallbackCommand command) {
        try {
            switch (command.action()) {
                case AUTO_COMPLETE -> fallbackExecutor.autoComplete(command.taskId(), command.processInstanceId());
                case AUTO_REJECT -> fallbackExecutor.autoReject(command.taskId(), command.processInstanceId());
                default -> throw new IllegalStateException("Unsupported fallback action: " + command.action());
            }
            commandRepository.markSucceeded(command.id());
        } catch (RuntimeException exception) {
            handleFailure(command, exception);
        }
    }

    private void handleFailure(AssignmentFallbackCommand command, RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if (command.attemptCount() >= MAX_ATTEMPTS) {
            commandRepository.markFailed(command.id(), message);
            LOGGER.error("Assignment fallback command {} failed permanently after {} attempts",
                    command.id(), command.attemptCount(), exception);
            return;
        }
        Duration retryDelay = retryDelay(command.attemptCount());
        commandRepository.reschedule(command.id(), retryDelay, message);
        LOGGER.warn("Assignment fallback command {} failed; retrying in {} seconds",
                command.id(), retryDelay.toSeconds(), exception);
    }

    private Duration retryDelay(int attemptCount) {
        long seconds = 1L << Math.min(Math.max(attemptCount - 1, 0), 8);
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
    }
}

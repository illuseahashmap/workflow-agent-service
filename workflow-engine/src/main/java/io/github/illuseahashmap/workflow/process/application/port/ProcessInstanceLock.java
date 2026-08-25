package io.github.illuseahashmap.workflow.process.application.port;

import java.util.function.Supplier;
import java.util.function.Function;

public interface ProcessInstanceLock {

    <T> T execute(String processInstanceId, Supplier<T> operation);

    /**
     * Executes with a lock context that can register validation inside the actual database
     * transaction. Implementations that do not expose ownership validation retain the legacy
     * behavior through the default adapter.
     */
    default <T> T executeWithContext(String processInstanceId, Function<LockContext, T> operation) {
        return execute(processInstanceId, () -> operation.apply(() -> { }));
    }

    interface LockContext {
        void registerCommitValidation();
    }
}

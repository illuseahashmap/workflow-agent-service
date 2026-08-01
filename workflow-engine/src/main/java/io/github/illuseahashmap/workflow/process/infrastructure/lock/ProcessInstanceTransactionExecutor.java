package io.github.illuseahashmap.workflow.process.infrastructure.lock;

import io.github.illuseahashmap.workflow.process.application.port.ProcessInstanceLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serializes process-instance commands and commits their transaction before releasing the lock.
 */
@Component
public class ProcessInstanceTransactionExecutor {

    private final ProcessInstanceLock processInstanceLock;
    private final TransactionTemplate transactionTemplate;

    public ProcessInstanceTransactionExecutor(ProcessInstanceLock processInstanceLock,
                                              PlatformTransactionManager transactionManager) {
        this.processInstanceLock = processInstanceLock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(String processInstanceId, Supplier<T> operation) {
        return processInstanceLock.execute(processInstanceId,
                () -> transactionTemplate.execute(status -> operation.get()));
    }
}

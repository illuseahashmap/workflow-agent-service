package io.github.illuseahashmap.workflow.process.application.port;

import java.util.function.Supplier;

public interface ProcessInstanceLock {

    <T> T execute(String processInstanceId, Supplier<T> operation);
}

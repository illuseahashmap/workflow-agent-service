package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Triggerable service-task delegate. Flowable waits until the completion consumer triggers it. */
@Component("agentTaskDelegate")
public class AgentTaskDelegate implements JavaDelegate {

    private final AgentTaskExecutionListener starter;

    public AgentTaskDelegate(AgentTaskExecutionListener starter) {
        this.starter = starter;
    }

    @Override
    public void execute(DelegateExecution execution) {
        starter.start(execution);
    }
}

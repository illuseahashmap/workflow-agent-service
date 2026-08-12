package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.port.AgentWorkflowExecutionPort;
import java.util.Map;
import java.util.Optional;
import org.flowable.engine.RuntimeService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Flowable adapter for the Agent completion recovery use case. */
@Component
public class FlowableAgentWorkflowExecutionAdapter implements AgentWorkflowExecutionPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RuntimeService runtimeService;

    public FlowableAgentWorkflowExecutionAdapter(
            NamedParameterJdbcTemplate jdbcTemplate, RuntimeService runtimeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeService = runtimeService;
    }

    @Override
    public Optional<WaitingExecution> lockWaitingExecution(
            String processInstanceId, String executionId, String activityId) {
        var locked = jdbcTemplate.queryForList("""
                SELECT ID_ FROM ACT_RU_EXECUTION
                WHERE ID_ = :executionId AND PROC_INST_ID_ = :processInstanceId
                FOR UPDATE
                """, Map.of("executionId", executionId, "processInstanceId", processInstanceId), String.class);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        var execution = runtimeService.createExecutionQuery().executionId(executionId).singleResult();
        if (execution == null || !activityId.equals(execution.getActivityId())) {
            return Optional.empty();
        }
        Object activation = runtimeService.getVariableLocal(
                executionId, AgentTaskExecutionListener.ACTIVATION_VARIABLE);
        return Optional.of(new WaitingExecution(
                executionId, execution.getActivityId(), activation == null ? null : activation.toString()));
    }

    @Override
    public void trigger(String executionId, Map<String, Object> variables) {
        runtimeService.trigger(executionId, variables);
    }

    @Override
    public void setLocalVariables(String executionId, Map<String, Object> variables) {
        runtimeService.setVariablesLocal(executionId, variables);
    }
}

package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.workflow.shared.event.IntegrationEventEnvelope;
import io.github.illuseahashmap.workflow.shared.event.ReliableEventPublisher;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AgentRunEventPublisherAdapter implements AgentRunEventPublisher {
    private final ReliableEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public AgentRunEventPublisherAdapter(ReliableEventPublisher publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void requested(AgentRun run) {
        publish("AgentRunRequested.v1", run, Map.of(
                "runId", run.id(), "status", run.status().name(),
                "agentVersionId", run.agentVersionId(),
                "processInstanceId", nullToEmpty(run.processInstanceId()),
                "executionId", nullToEmpty(run.executionId()),
                "activityId", nullToEmpty(run.activityId()),
                "activityActivationId", nullToEmpty(run.activityActivationId())));
    }

    @Override
    public void completed(AgentRun run, String outputSnapshotJson) {
        publish("AgentRunCompleted.v1", run, Map.of(
                "runId", run.id(), "status", run.status().name(),
                "attemptId", run.currentAttemptId() == null ? 0L : run.currentAttemptId(),
                "agentVersionId", run.agentVersionId(),
                "processInstanceId", nullToEmpty(run.processInstanceId()),
                "executionId", nullToEmpty(run.executionId()),
                "activityId", nullToEmpty(run.activityId()),
                "activityActivationId", nullToEmpty(run.activityActivationId()),
                "outputSnapshotJson", outputSnapshotJson == null ? "" : outputSnapshotJson));
    }

    private void publish(String type, AgentRun run, Map<String, Object> payload) {
        try {
            publisher.publish(new IntegrationEventEnvelope(
                    UUID.randomUUID(), type, "AgentRun", String.valueOf(run.id()),
                    run.tenantCode(), UUID.randomUUID().toString(),
                    objectMapper.writeValueAsString(payload), Instant.now()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Agent lifecycle event", exception);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

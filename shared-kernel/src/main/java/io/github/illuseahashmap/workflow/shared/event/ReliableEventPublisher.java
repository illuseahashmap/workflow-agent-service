package io.github.illuseahashmap.workflow.shared.event;

/** Application port for an event written in the same transaction as its aggregate change. */
@FunctionalInterface
public interface ReliableEventPublisher {

    void publish(IntegrationEventEnvelope event);
}

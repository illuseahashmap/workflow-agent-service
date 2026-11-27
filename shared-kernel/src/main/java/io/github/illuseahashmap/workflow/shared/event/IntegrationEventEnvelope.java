package io.github.illuseahashmap.workflow.shared.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable cross-context event metadata. Payload semantics belong to the owning bounded context. */
public record IntegrationEventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String tenantCode,
        String traceId,
        String payloadJson,
        Instant occurredAt
) {

    public IntegrationEventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        requireText(eventType, "eventType");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(tenantCode, "tenantCode");
        requireText(traceId, "traceId");
        requireText(payloadJson, "payloadJson");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

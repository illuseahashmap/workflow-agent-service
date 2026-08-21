package io.github.illuseahashmap.agent.runtime.application.port;

import java.time.Instant;
import java.util.Optional;

public interface AgentToolExecutionAuditRepository {

    Optional<Audit> findByIdempotencyKey(String tenantCode, String toolCode, String idempotencyKey);

    void save(Audit audit);

    record Audit(
            String tenantCode,
            String toolCode,
            String idempotencyKey,
            String argumentsHash,
            String status,
            String output,
            String errorCode,
            String traceId,
            Instant createdAt
    ) {
    }

    AgentToolExecutionAuditRepository NOOP = new AgentToolExecutionAuditRepository() {
        @Override
        public Optional<Audit> findByIdempotencyKey(String tenantCode, String toolCode, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public void save(Audit audit) {
            // Test-only compatibility adapter.
        }
    };
}

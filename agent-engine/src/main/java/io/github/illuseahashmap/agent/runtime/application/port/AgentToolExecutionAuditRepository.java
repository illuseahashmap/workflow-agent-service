package io.github.illuseahashmap.agent.runtime.application.port;

import java.time.Instant;
import java.util.Optional;

public interface AgentToolExecutionAuditRepository {

    Optional<Audit> findByIdempotencyKey(String tenantCode, String toolCode, String idempotencyKey);

    default Claim tryClaim(Audit reservation) {
        Optional<Audit> existing = findByIdempotencyKey(
                reservation.tenantCode(), reservation.toolCode(), reservation.idempotencyKey());
        if (existing.isPresent()) {
            if ("FAILED".equals(existing.get().status())
                    && reservation.argumentsHash().equals(existing.get().argumentsHash())) {
                save(reservation);
                return new Claim(true, reservation);
            }
            return new Claim(false, existing.get());
        }
        save(reservation);
        return new Claim(true, reservation);
    }

    default Claim tryClaim(Audit reservation, String claimOwner, Instant leaseExpiresAt, Instant now) {
        return tryClaim(reservation);
    }

    void save(Audit audit);

    default void complete(Audit audit) {
        save(audit);
    }

    default void complete(Audit audit, String claimOwner) {
        complete(audit);
    }

    record Claim(boolean acquired, Audit audit) {
    }

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

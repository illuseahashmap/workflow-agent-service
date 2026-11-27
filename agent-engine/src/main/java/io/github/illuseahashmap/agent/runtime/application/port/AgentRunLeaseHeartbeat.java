package io.github.illuseahashmap.agent.runtime.application.port;

import java.time.Duration;
import java.time.Instant;

/** Keeps ownership of a running Attempt alive while an external Agent call is in flight. */
public interface AgentRunLeaseHeartbeat {

    LeaseHandle start(LeaseCommand command);

    record LeaseCommand(
            String tenantCode,
            long runId,
            long attemptId,
            String leaseOwner,
            Duration leaseDuration,
            Instant deadlineAt
    ) {
    }

    interface LeaseHandle extends AutoCloseable {

        boolean isValid();

        @Override
        void close();
    }

    AgentRunLeaseHeartbeat NOOP = command -> new LeaseHandle() {
        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void close() {
            // No resources in tests or deliberately synchronous adapters.
        }
    };
}

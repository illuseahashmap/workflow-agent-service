package io.github.illuseahashmap.agent.runtime.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-08T01:00:00Z");

    @Test
    void createsQueuedRunWithImmutableIdentity() {
        AgentRun run = AgentRun.queued(
                1001L,
                "tenant-a",
                3001L,
                "idempotency-1",
                CREATED_AT.plusSeconds(600),
                CREATED_AT);

        assertThat(run.id()).isEqualTo(1001L);
        assertThat(run.tenantCode()).isEqualTo("tenant-a");
        assertThat(run.agentVersionId()).isEqualTo(3001L);
        assertThat(run.idempotencyKey()).isEqualTo("idempotency-1");
        assertThat(run.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(run.createdAt()).isEqualTo(CREATED_AT);
        assertThat(run.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(run.stateHistory()).isEmpty();
    }

    @Test
    void rejectsDeadlineThatDoesNotFollowCreation() {
        assertThatThrownBy(() -> AgentRun.queued(
                1001L,
                "tenant-a",
                3001L,
                "idempotency-1",
                CREATED_AT,
                CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deadlineAt must be after createdAt");
    }

    @Test
    void doesNotExposeMutableStateHistory() {
        AgentRun run = AgentRun.queued(
                1001L,
                "tenant-a",
                3001L,
                "idempotency-1",
                CREATED_AT.plusSeconds(600),
                CREATED_AT);

        assertThatThrownBy(() -> run.stateHistory().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AgentCompletionEventStoreIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private NamedParameterJdbcTemplate jdbcTemplate;
    private AgentCompletionEventStore eventStore;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/platform")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/platform").load().migrate();
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        eventStore = new AgentCompletionEventStore(
                jdbcTemplate, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void claimIsExclusiveAndExpiredLeaseCanBeReclaimed() {
        UUID eventId = insertCompletionEvent();

        assertThat(eventStore.claim("worker-a", 10, Duration.ofSeconds(1))).containsExactly(eventId);
        assertThat(eventStore.claim("worker-b", 10, Duration.ofSeconds(1))).isEmpty();
        jdbcTemplate.update("""
                UPDATE platform_outbox_event
                SET claim_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE event_id = :eventId
                """, Map.of("eventId", eventId));
        assertThat(eventStore.claim("worker-b", 10, Duration.ofSeconds(30))).containsExactly(eventId);
    }

    @Test
    void permanentFailureGoesToDeadLetterAndCanBeReplayed() {
        UUID eventId = insertCompletionEvent();
        eventStore.claim("worker-a", 10, Duration.ofSeconds(30));

        eventStore.retryOrDeadLetter(
                eventId, "worker-a", new IllegalStateException("bad mapping"), 8, true);

        assertThat(eventStore.deadLetters(10)).extracting(AgentCompletionEventStore.DeadLetterEvent::eventId)
                .containsExactly(eventId);
        assertThat(eventStore.replay(eventId)).isTrue();
        assertThat(eventStore.claim("worker-b", 10, Duration.ofSeconds(30))).containsExactly(eventId);
    }

    private UUID insertCompletionEvent() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform_outbox_event (
                    event_id, event_type, aggregate_type, aggregate_id, tenant_code,
                    trace_id, payload, status, next_attempt_at, created_at, updated_at
                ) VALUES (
                    :eventId, :eventType, 'AgentRun', '1', 'tenant-a',
                    'trace-a', CAST(:payload AS JSONB), 'QUEUED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """, Map.of("eventId", eventId, "eventType", AgentCompletionEventStore.EVENT_TYPE,
                "payload", "{\"runId\":1,\"attemptId\":1}"));
        return eventId;
    }
}

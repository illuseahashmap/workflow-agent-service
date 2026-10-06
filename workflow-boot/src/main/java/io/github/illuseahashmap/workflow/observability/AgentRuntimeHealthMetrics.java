package io.github.illuseahashmap.workflow.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes a platform-wide, low-cardinality runtime snapshot for alerting.
 * Tenant and run dimensions stay in the query API and are intentionally excluded from metrics.
 */
@Component
public class AgentRuntimeHealthMetrics {

    private static final String[] RUN_STATUSES = {
        "QUEUED", "RUNNING", "PAUSED", "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Map<String, AtomicLong> runCounts = new LinkedHashMap<>();
    private final AtomicLong pauseRequests = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong oldestQueueAgeSeconds = new AtomicLong();
    private final AtomicLong completionDeadLetters = new AtomicLong();
    private final AtomicLong completionReady = new AtomicLong();

    public AgentRuntimeHealthMetrics(NamedParameterJdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        for (String status : RUN_STATUSES) {
            AtomicLong value = new AtomicLong();
            runCounts.put(status, value);
            Gauge.builder("workflow.agent.runs.current", value, AtomicLong::get)
                    .tag("status", status)
                    .description("Current Agent runs by bounded lifecycle status")
                    .register(registry);
        }
        register(registry, "workflow.agent.pause.requests.current", pauseRequests,
                "Running Agent runs waiting for a cooperative pause boundary");
        register(registry, "workflow.agent.leases.expired.current", expiredLeases,
                "Running Agent runs whose worker lease has expired");
        register(registry, "workflow.agent.queue.oldest.age.seconds", oldestQueueAgeSeconds,
                "Age in seconds of the oldest queued Agent run");
        register(registry, "workflow.agent.completion.deadletters.current", completionDeadLetters,
                "Agent completion events awaiting operator resolution");
        register(registry, "workflow.agent.completion.ready.current", completionReady,
                "Agent completion events ready for delivery or retry");
    }

    @Scheduled(fixedDelayString = "${workflow.agent.metrics.refresh-delay-ms:15000}")
    public void refresh() {
        Map<String, Long> counts = jdbcTemplate.query("""
                SELECT status, COUNT(*) AS total
                FROM agent_run
                GROUP BY status
                """, Map.of(), resultSet -> {
            Map<String, Long> result = new LinkedHashMap<>();
            while (resultSet.next()) {
                result.put(resultSet.getString("status"), resultSet.getLong("total"));
            }
            return result;
        });
        runCounts.forEach((status, gauge) -> gauge.set(counts.getOrDefault(status, 0L)));

        pauseRequests.set(count("""
                SELECT COUNT(*) FROM agent_run
                WHERE status = 'RUNNING' AND pause_requested_at IS NOT NULL
                """));
        expiredLeases.set(count("""
                SELECT COUNT(*) FROM agent_run
                WHERE status = 'RUNNING' AND lease_expires_at <= CURRENT_TIMESTAMP
                """));
        OffsetDateTime oldest = jdbcTemplate.queryForObject("""
                SELECT MIN(created_at) FROM agent_run WHERE status = 'QUEUED'
                """, Map.of(), OffsetDateTime.class);
        oldestQueueAgeSeconds.set(oldest == null ? 0L
                : Math.max(0L, Duration.between(oldest, OffsetDateTime.now()).toSeconds()));
        completionDeadLetters.set(completionCount("status = 'DEAD_LETTER'"));
        completionReady.set(completionCount("status IN ('QUEUED', 'RETRY') AND next_attempt_at <= CURRENT_TIMESTAMP"));
    }

    private long count(String sql) {
        Long result = jdbcTemplate.queryForObject(sql, Map.of(), Long.class);
        return result == null ? 0L : result;
    }

    private long completionCount(String predicate) {
        return count("SELECT COUNT(*) FROM platform_outbox_event WHERE event_type = 'AgentRunCompleted.v1' AND "
                + predicate);
    }

    private void register(MeterRegistry registry, String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }
}

package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flowable.engine.RuntimeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composition-root recovery adapter for the first vertical slice. It is deliberately
 * idempotent: the marker is written only after Flowable accepts the resume operation.
 */
@Component
public class AgentFlowableCompletionCoordinator {

    private final JdbcTemplate jdbcTemplate;
    private final RuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    public AgentFlowableCompletionCoordinator(
            JdbcTemplate jdbcTemplate,
            RuntimeService runtimeService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${workflow.agent.workflow-resume-interval-ms:1000}")
    @Transactional
    public void resumeCompletedRuns() {
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            List<CompletedRun> runs = jdbcTemplate.query("""
                    SELECT id, tenant_code, process_instance_id, execution_id,
                           status, error_code, output_snapshot_json
                    FROM agent_run
                    WHERE trigger_type = 'FLOWABLE'
                      AND workflow_resumed_at IS NULL
                      AND process_instance_id IS NOT NULL
                      AND status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                    ORDER BY id
                    LIMIT 50
                    FOR UPDATE SKIP LOCKED
                    """, (rs, rowNum) -> map(rs));
            for (CompletedRun run : runs) {
                if ("SUCCEEDED".equals(run.status())) {
                    Map<String, Object> variables = new HashMap<>();
                    variables.put("agentRunId", run.id());
                    variables.put("agentRunStatus", run.status());
                    variables.put("agentOutput", parseOutput(run.outputSnapshotJson()));
                    runtimeService.trigger(run.executionId(), variables);
                } else {
                    runtimeService.deleteProcessInstance(
                            run.processInstanceId(), "Agent run failed: " + run.errorCode());
                }
                jdbcTemplate.update("""
                        UPDATE agent_run SET workflow_resumed_at = :resumedAt, updated_at = :resumedAt
                        WHERE id = :id AND tenant_code = :tenantCode AND workflow_resumed_at IS NULL
                        """, Map.of("id", run.id(), "tenantCode", run.tenantCode(),
                        "resumedAt", OffsetDateTime.now(ZoneOffset.UTC)));
            }
            return null;
        });
    }

    private CompletedRun map(ResultSet rs) throws java.sql.SQLException {
        return new CompletedRun(
                rs.getLong("id"), rs.getString("tenant_code"),
                rs.getString("process_instance_id"), rs.getString("execution_id"),
                rs.getString("status"), rs.getString("error_code"),
                rs.getString("output_snapshot_json"));
    }

    private Object parseOutput(String output) {
        try {
            return output == null ? Map.of() : objectMapper.readTree(output);
        } catch (Exception exception) {
            return Map.of("raw", output == null ? "" : output);
        }
    }

    private record CompletedRun(long id, String tenantCode, String processInstanceId,
                                String executionId, String status, String errorCode,
                                String outputSnapshotJson) { }
}

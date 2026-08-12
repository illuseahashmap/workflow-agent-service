package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.process.application.AgentCompletionContractException;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.AgentTaskExecutionListener;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class AgentCompletionFlowableTransactionIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private AgentFlowableCompletionProcessor processor;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.async-executor-activate", () -> "false");
        registry.add("workflow.agent.worker.enabled", () -> "false");
        registry.add("workflow.security.enabled", () -> "false");
        registry.add("workflow.auth.token.secret", () -> "integration-test-secret-at-least-32-bytes");
        registry.add("workflow.runtime.environment", () -> "test");
    }

    @Test
    void flowableInboxOutboxAndRunMarkerCommitTogether() {
        Fixture fixture = system(() -> fixture("{\"result\":\"decision\"}"));

        systemRun(() -> transactionTemplate.executeWithoutResult(
                status -> processor.process(fixture.eventId(), fixture.workerId())));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(fixture.processInstanceId()).singleResult()).isNull();
        system(() -> {
            assertThat(value("SELECT status FROM platform_outbox_event WHERE event_id = :id", fixture.eventId()))
                    .isEqualTo("DELIVERED");
            assertThat(value("SELECT completed_at::text FROM platform_inbox_event WHERE event_id = :id",
                    fixture.eventId())).isNotBlank();
            assertThat(value("SELECT workflow_resumed_at::text FROM agent_run WHERE id = :id",
                    fixture.runId())).isNotBlank();
            return null;
        });
    }

    @Test
    void mappingFailureRollsBackFlowableInboxOutboxAndRunMarker() {
        Fixture fixture = system(() -> fixture("{\"missing\":\"decision\"}"));

        assertThatThrownBy(() -> systemRun(() -> transactionTemplate.executeWithoutResult(
                status -> processor.process(fixture.eventId(), fixture.workerId()))))
                .isInstanceOf(AgentCompletionContractException.class);

        assertThat(runtimeService.createExecutionQuery().executionId(fixture.executionId()).singleResult())
                .isNotNull();
        system(() -> {
            assertThat(value("SELECT status FROM platform_outbox_event WHERE event_id = :id", fixture.eventId()))
                    .isEqualTo("PROCESSING");
            assertThat(count("SELECT COUNT(*) FROM platform_inbox_event WHERE event_id = :id", fixture.eventId()))
                    .isZero();
            assertThat(value("SELECT workflow_resumed_at::text FROM agent_run WHERE id = :id",
                    fixture.runId())).isNull();
            return null;
        });
    }

    private Fixture fixture(String outputMapping) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String processKey = "agent_completion_" + suffix;
        repositoryService.createDeployment().addBytes(processKey + ".bpmn20.xml",
                bpmn(processKey).getBytes(StandardCharsets.UTF_8)).deploy();
        var process = runtimeService.startProcessInstanceByKey(processKey);
        var execution = runtimeService.createExecutionQuery()
                .processInstanceId(process.getId()).activityId("agent-task").singleResult();
        String activationId = UUID.randomUUID().toString();
        runtimeService.setVariableLocal(
                execution.getId(), AgentTaskExecutionListener.ACTIVATION_VARIABLE, activationId);

        long providerId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_provider (tenant_code, provider_code, provider_name, provider_type)
                VALUES ('tenant-a', :code, 'Integration Provider', 'MOCK') RETURNING id
                """, Map.of("code", "provider-" + suffix), Long.class);
        long definitionId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_definition (tenant_code, agent_code, agent_name)
                VALUES ('tenant-a', :code, 'Integration Agent') RETURNING id
                """, Map.of("code", "agent-" + suffix), Long.class);
        long versionId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_definition_version (
                    tenant_code, definition_id, version, status, provider_id, output_schema)
                VALUES ('tenant-a', :definitionId, 1, 'PUBLISHED', :providerId,
                    '{"type":"object","properties":{"result":{"type":"string"}}}') RETURNING id
                """, Map.of("definitionId", definitionId, "providerId", providerId), Long.class);
        long runId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_run (
                    tenant_code, idempotency_key, agent_version_id, status, trigger_type,
                    process_instance_id, execution_id, activity_id, activity_activation_id,
                    deadline_at, completed_at, result_status, output_snapshot_json,
                    output_mapping_json, process_failure_policy)
                VALUES ('tenant-a', :key, :versionId, 'SUCCEEDED', 'FLOWABLE',
                    :processId, :executionId, 'agent-task', :activationId,
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes', CURRENT_TIMESTAMP, 'SUCCESS',
                    '{"content":{"result":"APPROVE"}}', CAST(:mapping AS jsonb),
                    'HOLD_FOR_OPERATIONS') RETURNING id
                """, map("key", "run-" + suffix, "versionId", versionId,
                "processId", process.getId(), "executionId", execution.getId(),
                "activationId", activationId, "mapping", outputMapping), Long.class);
        long attemptId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_run_attempt (
                    tenant_code, agent_run_id, attempt_no, status, started_at, completed_at)
                VALUES ('tenant-a', :runId, 1, 'SUCCEEDED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Map.of("runId", runId), Long.class);
        jdbcTemplate.update("UPDATE agent_run SET current_attempt_id = :attemptId WHERE id = :runId",
                Map.of("attemptId", attemptId, "runId", runId));

        UUID eventId = UUID.randomUUID();
        String workerId = "integration-worker";
        jdbcTemplate.update("""
                INSERT INTO platform_outbox_event (
                    event_id, event_type, aggregate_type, aggregate_id, tenant_code, trace_id,
                    payload, status, next_attempt_at, claimed_by, claimed_at, claim_expires_at,
                    created_at, updated_at)
                VALUES (:eventId, :eventType, 'AgentRun', :runId, 'tenant-a', :traceId,
                    CAST(:payload AS jsonb), 'PROCESSING', CURRENT_TIMESTAMP, :workerId,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 minute',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, map("eventId", eventId, "eventType", AgentCompletionEventStore.EVENT_TYPE,
                "runId", Long.toString(runId), "traceId", "trace-" + suffix,
                "payload", "{\"runId\":" + runId + ",\"attemptId\":" + attemptId
                        + ",\"activityActivationId\":\"" + activationId + "\"}",
                "workerId", workerId));
        return new Fixture(eventId, workerId, runId, process.getId(), execution.getId());
    }

    private String value(String sql, Object id) {
        return jdbcTemplate.queryForObject(sql, Map.of("id", id), String.class);
    }

    private int count(String sql, Object id) {
        return jdbcTemplate.queryForObject(sql, Map.of("id", id), Integer.class);
    }

    private Map<String, Object> map(Object... entries) {
        var values = new HashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private <T> T system(java.util.function.Supplier<T> action) {
        return TrustedDataAccessContext.runAsSystemWorker(action);
    }

    private void systemRun(Runnable action) {
        TrustedDataAccessContext.runAsSystemWorker(action);
    }

    private String bpmn(String processKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    targetNamespace="agent-completion-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow id="to-agent" sourceRef="start" targetRef="agent-task" />
                    <receiveTask id="agent-task" />
                    <sequenceFlow id="to-end" sourceRef="agent-task" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """.formatted(processKey);
    }

    private record Fixture(
            UUID eventId, String workerId, long runId, String processInstanceId, String executionId) {
    }
}

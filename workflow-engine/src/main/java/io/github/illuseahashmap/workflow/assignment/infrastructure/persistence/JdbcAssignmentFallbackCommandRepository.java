package io.github.illuseahashmap.workflow.assignment.infrastructure.persistence;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackAction;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackCommand;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackCommandRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssignmentFallbackCommandRepository implements AssignmentFallbackCommandRepository {

    private static final int MAX_FAILURE_MESSAGE_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAssignmentFallbackCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void enqueue(String tenantId, String taskId, String processInstanceId, AssignmentFallbackAction action) {
        jdbcTemplate.update("""
                INSERT INTO workflow_assignment_fallback_command
                    (tenant_id, task_id, process_instance_id, action, status,
                     attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (task_id) DO NOTHING
                """, tenantId, taskId, processInstanceId, action.name());
    }

    @Override
    public AssignmentFallbackCommand claimNext(Duration processingTimeout) {
        try {
            return jdbcTemplate.queryForObject("""
                    WITH candidate AS (
                        SELECT id
                        FROM workflow_assignment_fallback_command
                        WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                           OR (status = 'PROCESSING'
                               AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond'))
                        ORDER BY next_attempt_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE workflow_assignment_fallback_command command
                    SET status = 'PROCESSING',
                        attempt_count = attempt_count + 1,
                        claimed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    FROM candidate
                    WHERE command.id = candidate.id
                    RETURNING command.id, command.tenant_id, command.task_id,
                              command.process_instance_id, command.action, command.attempt_count
                    """, this::mapCommand, processingTimeout.toMillis());
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    @Override
    public void markSucceeded(long commandId) {
        jdbcTemplate.update("""
                UPDATE workflow_assignment_fallback_command
                SET status = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP,
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """, commandId);
    }

    @Override
    public void reschedule(long commandId, Duration delay, String failureMessage) {
        jdbcTemplate.update("""
                UPDATE workflow_assignment_fallback_command
                SET status = 'PENDING',
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    claimed_at = NULL, last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """, delay.toMillis(), truncate(failureMessage), commandId);
    }

    @Override
    public void markFailed(long commandId, String failureMessage) {
        jdbcTemplate.update("""
                UPDATE workflow_assignment_fallback_command
                SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                    last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """, truncate(failureMessage), commandId);
    }

    private AssignmentFallbackCommand mapCommand(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AssignmentFallbackCommand(
                resultSet.getLong("id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("task_id"),
                resultSet.getString("process_instance_id"),
                AssignmentFallbackAction.valueOf(resultSet.getString("action")),
                resultSet.getInt("attempt_count"));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_FAILURE_MESSAGE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }
}

package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.ArrayList;
import java.util.List;

/** Scope timeouts to individual statements; never mutate the shared JDBC template. */
final class DeadlineQuery {
    private DeadlineQuery() {}

    static <T> List<T> query(
            NamedParameterJdbcTemplate jdbc,
            String sql,
            SqlParameterSource parameters,
            RowMapper<T> mapper) {
        var remaining = ExecutionDeadline.remainingOrNull();
        if (remaining == null) {
            return jdbc.query(sql, parameters, mapper);
        }
        try {
            return jdbc.execute(
                    sql,
                    parameters,
                    statement -> {
                        var current = ExecutionDeadline.remainingOrNull();
                        int seconds =
                                (int)
                                        Math.min(
                                                Integer.MAX_VALUE,
                                                Math.max(1, (current.toMillis() + 999) / 1000));
                        int existing = statement.getQueryTimeout();
                        statement.setQueryTimeout(
                                existing > 0 ? Math.min(existing, seconds) : seconds);
                        var results = new ArrayList<T>();
                        try (var rows = statement.executeQuery()) {
                            int number = 0;
                            while (rows.next()) {
                                ExecutionDeadline.remainingOrNull();
                                results.add(mapper.mapRow(rows, number++));
                            }
                        }
                        ExecutionDeadline.remainingOrNull();
                        return results;
                    });
        } catch (org.springframework.dao.QueryTimeoutException timeout) {
            throw new ExecutionDeadline.Exceeded();
        }
    }
}

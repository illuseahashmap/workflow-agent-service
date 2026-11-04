package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.postgresql.jdbc.PgStatement;

/** PostgreSQL millisecond cancellation; no session settings or pooled-connection state are changed. */
final class DeadlineAwareStatements {
    private DeadlineAwareStatements() {}

    static Limit limit(Statement statement) throws SQLException {
        var remaining = ExecutionDeadline.remainingOrNull();
        if (remaining == null) { return new Limit(null, 0); }
        long nanos = remaining.toNanos();
        long millis = nanos / 1_000_000 + (nanos % 1_000_000 == 0 ? 0 : 1);
        var postgres = statement.unwrap(PgStatement.class);
        long configured = postgres.getQueryTimeoutMs();
        postgres.setQueryTimeoutMs(configured > 0 ? Math.min(configured, millis) : millis);
        return new Limit(postgres, configured);
    }

    static Statement wrap(Statement statement) {
        Class<?> contract = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> {
                    try (var limit = method.getName().startsWith("execute") ? limit(statement) : new Limit(null, 0)) {
                        try {
                            return method.invoke(statement, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }
                });
    }

    record Limit(PgStatement postgres, long previous) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            if (postgres != null && !postgres.isClosed()) { postgres.setQueryTimeoutMs(previous); }
        }
    }
}

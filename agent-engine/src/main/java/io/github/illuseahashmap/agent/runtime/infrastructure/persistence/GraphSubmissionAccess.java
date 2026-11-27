package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.function.Supplier;

/** Trusted internal graph bookkeeping inside an already authorized tenant transaction. */
final class GraphSubmissionAccess {
    private GraphSubmissionAccess() {}

    static <T> T execute(NamedParameterJdbcTemplate jdbc, Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Graph bookkeeping requires the business transaction");
        }
        String previous =
                jdbc.queryForObject(
                        "SELECT coalesce(current_setting('app.system_worker',true),'false')",
                        Map.of(),
                        String.class);
        jdbc.queryForObject(
                "SELECT set_config('app.system_worker','true',true)", Map.of(), String.class);
        Throwable failure = null;
        try {
            return action.get();
        } catch (RuntimeException | Error caught) {
            failure = caught;
            throw caught;
        } finally {
            try {
                jdbc.queryForObject(
                        "SELECT set_config('app.system_worker',:previous,true)",
                        Map.of("previous", previous),
                        String.class);
            } catch (RuntimeException restore) {
                // PostgreSQL aborts the transaction on SQL errors; rollback also removes SET LOCAL.
                if (failure == null) {
                    throw restore;
                }
                failure.addSuppressed(restore);
            }
        }
    }
}

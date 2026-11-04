package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgStatement;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadlineAwareStatementsTest {
    @Test
    void expirationDuringConnectionAcquisitionClosesTheConnectionWithoutIssuingRlsSql() throws Exception {
        var clock = new AtomicLong();
        var connection = mock(java.sql.Connection.class);
        var statement = mock(PreparedStatement.class);
        var source = mock(javax.sql.DataSource.class);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(source.getConnection()).thenAnswer(invocation -> {
            clock.set(Duration.ofSeconds(2).toNanos());
            return connection;
        });
        var wrapped = (javax.sql.DataSource) new TenantRlsDataSourcePostProcessor().postProcessAfterInitialization(source, "dataSource");
        try (var budget = ExecutionDeadline.open(Duration.ofSeconds(1), clock::get)) {
            assertThatThrownBy(wrapped::getConnection).isInstanceOf(ExecutionDeadline.Exceeded.class);
        }
        verify(statement, never()).execute();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void usesRemainingMillisecondsAndRestoresTheOriginalTimeoutAfterFailure() throws Exception {
        var clock = new AtomicLong();
        var raw = mock(PreparedStatement.class);
        var pg = mock(PgStatement.class);
        when(raw.unwrap(PgStatement.class)).thenReturn(pg);
        when(pg.getQueryTimeoutMs()).thenReturn(5000L);
        when(raw.execute()).thenThrow(new java.sql.SQLException("query cancelled", "57014"));
        var wrapped = (PreparedStatement) DeadlineAwareStatements.wrap(raw);
        try (var budget = ExecutionDeadline.open(Duration.ofMillis(500), clock::get)) {
            clock.set(Duration.ofMillis(350).toNanos());
            assertThatThrownBy(wrapped::execute).isInstanceOf(java.sql.SQLException.class);
        }
        verify(pg).setQueryTimeoutMs(150);
        verify(pg).setQueryTimeoutMs(5000);
        try (var budget = ExecutionDeadline.open(Duration.ofNanos(1), clock::get)) {
            clock.incrementAndGet();
            assertThatThrownBy(wrapped::execute).isInstanceOf(ExecutionDeadline.Exceeded.class);
        }
        verify(raw, org.mockito.Mockito.times(1)).execute();
    }

    @Test
    void doesNotExtendAnExistingStricterTimeoutOrTouchUnbudgetedStatements() throws Exception {
        var raw = mock(PreparedStatement.class);
        var pg = mock(PgStatement.class);
        when(raw.unwrap(PgStatement.class)).thenReturn(pg);
        when(pg.getQueryTimeoutMs()).thenReturn(10L);
        var wrapped = (PreparedStatement) DeadlineAwareStatements.wrap(raw);
        wrapped.execute();
        verify(raw, never()).unwrap(PgStatement.class);
        try (var budget = ExecutionDeadline.open(Duration.ofSeconds(1), System::nanoTime)) { wrapped.execute(); }
        verify(pg, org.mockito.Mockito.times(2)).setQueryTimeoutMs(10);
    }
}

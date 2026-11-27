package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresRetrievalTraceRepositoryTest {

    @Test
    void bindsCreatedAtAsJdbcTimestamp() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PostgresRetrievalTraceRepository repository =
                new PostgresRetrievalTraceRepository(jdbcTemplate, new ObjectMapper());
        Instant createdAt = Instant.parse("2026-09-12T02:07:12Z");

        repository.save(new RetrievalTrace(
                "trace-1", "default", "query-fingerprint", List.of("purchase-policy"),
                RetrievalStatus.SUCCESS, "KEYWORD", 2, createdAt));

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), parameters.capture());
        Object boundCreatedAt = parameters.getValue().getValue("createdAt");
        assertThat(boundCreatedAt).isInstanceOf(Timestamp.class);
        assertThat(((Timestamp) boundCreatedAt).toInstant()).isEqualTo(createdAt);
    }
}

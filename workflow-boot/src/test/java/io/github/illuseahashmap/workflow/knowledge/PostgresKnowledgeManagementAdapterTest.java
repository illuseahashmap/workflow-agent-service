package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresKnowledgeManagementAdapterTest {

    @Test
    void returnsTenantScopedEmptyPagesAndCatalogs() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        doReturn(List.of()).when(jdbc).query(anyString(), any(MapSqlParameterSource.class),
                any(RowMapper.class));
        PostgresKnowledgeManagementAdapter adapter = new PostgresKnowledgeManagementAdapter(jdbc);

        var profiles = adapter.profiles("tenant-a", 1, 20, "policy", "PUBLISHED");
        var jobs = adapter.ingestionJobs("tenant-a", 2, 10, "QUEUED");

        assertThat(profiles.total()).isZero();
        assertThat(profiles.records()).isEmpty();
        assertThat(jobs.pageNum()).isEqualTo(2);
        assertThat(jobs.records()).isEmpty();
        assertThat(adapter.indexVersions("tenant-a", "ACTIVE")).isEmpty();
        assertThat(adapter.grants("tenant-a", "user-1")).isEmpty();
    }

    @Test
    void rejectsInvalidCommandsBeforeWriting() {
        PostgresKnowledgeManagementAdapter adapter =
                new PostgresKnowledgeManagementAdapter(mock(NamedParameterJdbcTemplate.class));

        assertThatThrownBy(() -> adapter.createProfile("tenant-a",
                new KnowledgeManagementPort.CreateProfile(
                        "profile", "KEYWORD", 0, 0.2, 1L, List.of("policies"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid retrieval limits");
        assertThatThrownBy(() -> adapter.createDocument("tenant-a",
                new KnowledgeManagementPort.CreateDocument(
                        "", "Policies", "document-1", 1, "hash", "content")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceCode must not be blank");
    }

    @Test
    void writesAndRevokesScopeGrant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresKnowledgeManagementAdapter adapter = new PostgresKnowledgeManagementAdapter(jdbc);
        var grant = new KnowledgeManagementPort.GrantScope("user-1", "policies");

        adapter.grant("tenant-a", grant);
        adapter.revoke("tenant-a", grant);

        verify(jdbc, org.mockito.Mockito.times(2))
                .update(anyString(), any(MapSqlParameterSource.class));
    }
}

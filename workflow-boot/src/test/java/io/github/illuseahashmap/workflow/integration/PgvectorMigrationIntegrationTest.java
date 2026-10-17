package io.github.illuseahashmap.workflow.integration;

import io.github.illuseahashmap.workflow.config.FlywayMigrationConfig;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the opt-in migration against PostgreSQL 18 with the real pgvector extension. */
@Testcontainers(disabledWithoutDocker = true)
class PgvectorMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg18-trixie");

    @Test
    void installsVectorSchemaAndChecksActualVectorDimension() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector")
                .load().migrate();
        // A deployment may temporarily return to keyword-only mode after the vector migration ran.
        new FlywayMigrationConfig().workflowFlyway(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), false, "0", new MockEnvironment());
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT vector_dims('[1,2,3]'::vector),
                            to_regclass('knowledge_chunk_embedding') IS NOT NULL,
                            (SELECT relrowsecurity AND relforcerowsecurity
                             FROM pg_class WHERE oid = 'knowledge_chunk_embedding'::regclass)
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(3);
            assertThat(result.getBoolean(2)).isTrue();
            assertThat(result.getBoolean(3)).isTrue();
        }
    }
}

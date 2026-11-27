package io.github.illuseahashmap.workflow.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

abstract class KnowledgeDatabaseFixture {
    protected static PostgreSQLContainer<?> postgres;
    protected static String databaseUrl;
    protected static String databaseUser;
    protected static String databasePassword;

    @org.junit.jupiter.api.BeforeAll
    static void database() {
        databaseUrl = System.getenv("WORKFLOW_TEST_DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.isBlank()) {
            databaseUser = System.getenv("WORKFLOW_TEST_DATABASE_USER");
            databasePassword = System.getenv("WORKFLOW_TEST_DATABASE_PASSWORD");
        } else {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),
                    "Docker or an explicitly configured isolated test database is required");
            postgres =
                    new PostgreSQLContainer<>(
                            org.testcontainers.utility.DockerImageName.parse(
                                            "pgvector/pgvector:pg18-trixie")
                                    .asCompatibleSubstituteFor("postgres"));
            postgres.start();
            databaseUrl = postgres.getJdbcUrl();
            databaseUser = postgres.getUsername();
            databasePassword = postgres.getPassword();
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void closeDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @FunctionalInterface
    protected interface DatabaseCheck {
        void run(String url) throws Exception;
    }

    protected void withScratchDatabase(DatabaseCheck check) throws Exception {
        // Readers use separate sessions. A private database makes committed data visible to
        // them without leaving corpus rows or changing planner statistics in the shared test DB.
        String name = "specimen_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
        String url =
                "jdbc:postgresql://"
                        + java.net.URI.create(databaseUrl.substring(5)).getRawAuthority()
                        + "/"
                        + name;
        admin.execute("CREATE DATABASE " + name);
        try {
            check.run(url);
        } finally {
            admin.execute("DROP DATABASE " + name);
        }
    }
}

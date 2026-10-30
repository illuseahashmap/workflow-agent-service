package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentContent;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentImmutabilityIntegrationTest {
    private final String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
    private static PostgreSQLContainer<?> postgres;
    private static String databaseUrl;
    private static String databaseUser;
    private static String databasePassword;

    @org.junit.jupiter.api.BeforeAll
    static void database() {
        databaseUrl = System.getenv("WORKFLOW_TEST_DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.isBlank()) {
            databaseUser = System.getenv("WORKFLOW_TEST_DATABASE_USER");
            databasePassword = System.getenv("WORKFLOW_TEST_DATABASE_PASSWORD");
        } else {
            org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),
                    "Docker or an explicitly configured isolated test database is required");
            postgres = new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
                    .parse("pgvector/pgvector:pg18-trixie").asCompatibleSubstituteFor("postgres"));
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

    @Test
    void sourceSnapshotsPreserveDocumentsVectorsAndFrozenHistoryAndSerializeProfileVersions() throws Exception {
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var template = new NamedParameterJdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var adapter = new PostgresKnowledgeManagementAdapter(template);
        var indexes = new PostgresKnowledgeIndexAdapter(template);
        String tenant = "snapshot-" + suffix;
        var first = transaction.execute(status -> adapter.createDocument(tenant, command("alpha policy")));
        var service = new io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService(
                new PostgresKnowledgeDocumentStore(template), indexes,
                new io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker(128, 16),
                texts -> new io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort.Batch(
                        "test:v1", 2, texts.stream().map(text -> java.util.List.of(1.0f, 0.0f)).toList()));
        var firstIndex = transaction.execute(status -> service.ingest(tenant, first.sourceCode(), first.id()).indexVersion());
        transaction.executeWithoutResult(status -> indexes.activate(firstIndex));
        var second = transaction.execute(status -> adapter.createDocument(tenant,
                new KnowledgeManagementPort.CreateDocument(first.sourceCode(), "Policies", "doc-2", 1,
                        DocumentContent.from("beta policy").sha256(), "beta policy")));
        var secondIndex = transaction.execute(status -> service.ingest(tenant, second.sourceCode(), second.id()).indexVersion());
        transaction.executeWithoutResult(status -> indexes.activate(secondIndex));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT document_version_id) FROM knowledge_chunk WHERE index_version_id=?",
                Integer.class, secondIndex.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk_embedding WHERE index_version_id=?",
                Integer.class, secondIndex.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk WHERE index_version_id=?",
                Integer.class, firstIndex.id())).isEqualTo(1);
        var request = new io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest("alpha",
                java.util.List.of(first.sourceCode()), java.util.List.of(), null, 10,
                io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint.KEYWORD,
                0, java.util.List.of(), secondIndex.id());
        assertThat(new PostgresKeywordChunkSearchAdapter(template).search(tenant, request, 10)).isNotEmpty();
        var pending = transaction.execute(status -> service.ingest(tenant, second.sourceCode(), second.id()).indexVersion());
        transaction.execute(status -> adapter.createDocument(tenant,
                new KnowledgeManagementPort.CreateDocument(first.sourceCode(), "Policies", "doc-3", 1,
                        DocumentContent.from("beta policy").sha256(), "beta policy")));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> indexes.activate(pending)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("snapshot");
        var newer = transaction.execute(status -> adapter.createDocument(tenant,
                new KnowledgeManagementPort.CreateDocument(first.sourceCode(), "Policies", "doc-1", 2,
                        DocumentContent.from("new alpha").sha256(), "new alpha")));
        var rebuilt = transaction.execute(status -> service.ingest(tenant, newer.sourceCode(), newer.id()).indexVersion());
        transaction.executeWithoutResult(status -> indexes.activate(rebuilt));
        var members = jdbc.queryForList("SELECT DISTINCT document_version_id FROM knowledge_chunk WHERE index_version_id=?",
                Long.class, rebuilt.id());
        assertThat(members).contains(newer.id(), second.id()).doesNotContain(first.id());
        assertThat(members).hasSize(3);
        assertThatThrownBy(() -> service.ingest(tenant, first.sourceCode(), first.id()))
                .isInstanceOf(io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSupersededException.class);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> {
                start.await();
                return transaction.execute(status -> adapter.createProfile(tenant,
                        new KnowledgeManagementPort.CreateProfile("race", "KEYWORD", 10, 0,
                                rebuilt.id(), java.util.List.of(first.sourceCode())))).version();
            })).toList();
            start.countDown();
            var versions = new java.util.HashSet<Integer>();
            for (var task : tasks) { versions.add(task.get()); }
            assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        }
        var profileCommand = new KnowledgeManagementPort.CreateProfile("race", "KEYWORD", 10, 0,
                rebuilt.id(), java.util.List.of(first.sourceCode()));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            adapter.createProfile(tenant, profileCommand);
            throw new IllegalStateException("injected rollback");
        })).isInstanceOf(IllegalStateException.class);
        var afterRollback = transaction.execute(status -> adapter.createProfile(tenant, profileCommand));
        assertThat(afterRollback.version()).isEqualTo(9);
    }

    @Test
    void activationRollsBackWhenCompletionWriteFails() {
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new JdbcTemplate(source);
        var template = new NamedParameterJdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var adapter = new PostgresKnowledgeManagementAdapter(template);
        String tenant = "atomic-" + suffix;
        var document = transaction.execute(status -> adapter.createDocument(tenant, command("atomic text")));
        var indexes = new PostgresKnowledgeIndexAdapter(template);
        var jobs = new PostgresIngestionJobRepository(template, indexes);
        var now = java.time.Instant.now();
        var job = jobs.claimNext(tenant, now, now.plusSeconds(60)).orElseThrow();
        var snapshot = new PostgresKnowledgeDocumentStore(template).loadSnapshot(tenant, document.sourceCode(), document.id());
        var chunks = new io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker(128, 16).chunk(snapshot);
        var index = transaction.execute(status -> indexes.build(snapshot.documentVersion(), chunks));
        String function = "fail_completion_" + suffix;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id=" + job.id() + " AND NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'injected completion failure'; "
                + "END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE UPDATE ON knowledge_ingestion_job FOR EACH ROW EXECUTE FUNCTION "
                + function + "()");
        try {
            assertThatThrownBy(() -> transaction.execute(status -> jobs.complete(job, index)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM knowledge_index_version WHERE id=?", String.class, index.id()))
                    .isEqualTo("CANDIDATE");
            assertThat(jdbc.queryForObject("SELECT status FROM knowledge_ingestion_job WHERE id=?", String.class, job.id()))
                    .isEqualTo("RUNNING");
        } finally {
            jdbc.execute("DROP TRIGGER " + function + " ON knowledge_ingestion_job");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
        Boolean completed = transaction.execute(status -> jobs.complete(job, index));
        assertThat(completed).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_index_version WHERE id=?", String.class, index.id()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void upgradesLegacyJobsAndRejectsAmbiguousDocumentIdentity() {
        for (boolean ambiguous : java.util.List.of(false, true)) {
            String name = "upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
            var admin = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
            admin.execute("CREATE DATABASE " + name);
            var authority = java.net.URI.create(databaseUrl.substring(5)).getRawAuthority();
            String url = "jdbc:postgresql://" + authority + "/" + name;
            Flyway.configure().dataSource(url, databaseUser, databasePassword)
                    .locations("classpath:db/migration/platform").target("54").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, databaseUser, databasePassword));
            jdbc.update("INSERT INTO knowledge_source(tenant_code,source_code,name) VALUES('default','upgrade','Upgrade')");
            Long legacyIndex = jdbc.queryForObject("""
                    INSERT INTO knowledge_index_version(tenant_code,source_code,version,embedding_model,status)
                    VALUES('default','upgrade',1,'none','ACTIVE') RETURNING id
                    """, Long.class);
            jdbc.update("""
                    INSERT INTO knowledge_retrieval_profile_version
                    (tenant_code,profile_code,version,strategy,max_results,minimum_score,index_version_id,status)
                    VALUES('default','legacy-profile',7,'KEYWORD',10,0,?,'PUBLISHED')
                    """, legacyIndex);
            Long documentId = jdbc.queryForObject("""
                    INSERT INTO knowledge_document_version(tenant_code,source_code,external_document_id,version,content_hash,status)
                    VALUES('default','upgrade','doc-1',1,'legacy-hash','READY') RETURNING id
                    """, Long.class);
            if (ambiguous) {
                jdbc.update("""
                        INSERT INTO knowledge_document_version(tenant_code,source_code,external_document_id,version,content_hash,status)
                        VALUES('default','upgrade','doc-2',1,'legacy-hash','READY')
                        """);
            }
            jdbc.update("""
                    INSERT INTO knowledge_ingestion_job(tenant_code,source_code,document_hash,status,attempt)
                    VALUES('default','upgrade','legacy-hash','RUNNING',1)
                    """);
            var upgrade = Flyway.configure().dataSource(url, databaseUser, databasePassword)
                    .locations("classpath:db/migration/platform").load();
            if (ambiguous) {
                assertThatThrownBy(upgrade::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='knowledge_ingestion_job' AND column_name='document_version_id'
                        """, Integer.class)).isZero();
            } else {
                upgrade.migrate();
                assertThat(jdbc.queryForObject("SELECT document_version_id FROM knowledge_ingestion_job", Long.class))
                        .isEqualTo(documentId);
                assertThat(jdbc.queryForObject("SELECT attempt FROM knowledge_ingestion_job", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT status FROM knowledge_ingestion_job", String.class)).isEqualTo("QUEUED");
                var adapter = new PostgresKnowledgeManagementAdapter(new NamedParameterJdbcTemplate(jdbc));
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
                var profile = transaction.execute(status -> adapter.createProfile("default",
                        new KnowledgeManagementPort.CreateProfile("legacy-profile", "KEYWORD", 10, 0,
                                legacyIndex, java.util.List.of("upgrade"))));
                assertThat(profile.version()).isEqualTo(8);
            }
        }
    }

    @Test
    void toolSnapshotsAreImmutableAndCannotReferenceAnotherTenantVersion() {
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new JdbcTemplate(source);
        Long definition = jdbc.queryForObject("""
                INSERT INTO agent_definition(tenant_code,agent_code,agent_name)
                VALUES('default',?,'Snapshot') RETURNING id
                """, Long.class, "snapshot-test-" + suffix);
        Long version = jdbc.queryForObject("""
                INSERT INTO agent_definition_version(tenant_code,definition_id,version)
                VALUES('default',?,1) RETURNING id
                """, Long.class, definition);
        var snapshots = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolSnapshotRepository(
                new NamedParameterJdbcTemplate(source));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var original = new io.github.illuseahashmap.agent.runtime.application.port.AgentToolDefinition(
                "lookup", "Original", "{\"type\":\"object\"}", true);
        transaction.executeWithoutResult(status -> snapshots.freeze("default", version, java.util.List.of(original)));
        assertThat(snapshots.find("default", version)).hasSize(1);
        assertThat(snapshots.find("default", version).getFirst().toolName()).isEqualTo(original.toolName());
        assertThat(snapshots.find("other", version)).isEmpty();
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_version_tool_snapshot SET tool_name='Changed' WHERE agent_version_id=?", version))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> snapshots.freeze("other", version, java.util.List.of(original))))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }

    @Test
    void expiredClaimCannotOverwriteReplacementOrActivateIndex() {
        String tenant = "lease-" + suffix;
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new JdbcTemplate(source);
        var template = new NamedParameterJdbcTemplate(source);
        var adapter = new PostgresKnowledgeManagementAdapter(template);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var document = transaction.execute(status -> adapter.createDocument(tenant,
                new KnowledgeManagementPort.CreateDocument("lease-test-" + suffix, "Lease", "doc", 1,
                        DocumentContent.from("lease text").sha256(), "lease text")));
        var indexes = org.mockito.Mockito.mock(PostgresKnowledgeIndexAdapter.class);
        var jobs = new PostgresIngestionJobRepository(template, indexes);
        var now = java.time.Instant.now();
        var old = jobs.claimNext(tenant, now, now.plusSeconds(60)).orElseThrow();
        jdbc.update("UPDATE knowledge_ingestion_job SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                document.ingestionJobId());
        var replacement = jobs.claimNext(tenant, now.plusSeconds(1), now.plusSeconds(61)).orElseThrow();
        assertThat(replacement.attempt()).isGreaterThan(old.attempt());
        assertThat(jobs.save(old.succeeded())).isFalse();
        var index = new io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion(
                1, tenant, "lease-test-" + suffix, 1, "none", "CANDIDATE", now);
        Boolean completed = transaction.execute(status -> jobs.complete(old, index));
        assertThat(completed).isFalse();
        org.mockito.Mockito.verifyNoInteractions(indexes);
        assertThat(jobs.renew(old, now.plusSeconds(120))).isFalse();
        assertThat(jobs.save(replacement.failed("EXPECTED_TEST_FAILURE"))).isTrue();
    }

    @Test
    void repeatedSubmissionPreservesContentAndCompletedJob() {
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new JdbcTemplate(source);
        var adapter = new PostgresKnowledgeManagementAdapter(new NamedParameterJdbcTemplate(source));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var first = transaction.execute(status -> adapter.createDocument("default", command("policy text")));
        jdbc.update("UPDATE knowledge_ingestion_job SET status='SUCCEEDED', attempt=2 WHERE id=?", first.ingestionJobId());
        var repeated = transaction.execute(status -> adapter.createDocument("default", command("policy text")));
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.ingestionJobId()).isEqualTo(first.ingestionJobId());
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_ingestion_job WHERE id=?", String.class,
                first.ingestionJobId())).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> transaction.execute(status -> adapter.createDocument("default", command("changed text"))))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_document_content WHERE document_version_id=?",
                String.class, first.id())).isEqualTo("policy text");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE knowledge_document_content SET content='overwrite' WHERE document_version_id=?", first.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var other = transaction.execute(status -> adapter.createDocument("default",
                new KnowledgeManagementPort.CreateDocument(first.sourceCode(), "Policies", "doc-2", 1,
                        DocumentContent.from("policy text").sha256(), "policy text")));
        assertThat(other.ingestionJobId()).isNotEqualTo(first.ingestionJobId());
        var store = new PostgresKnowledgeDocumentStore(new NamedParameterJdbcTemplate(source));
        assertThat(store.loadSnapshot("default", first.sourceCode(), other.id()).documentVersion().id())
                .isEqualTo(other.id());
    }

    private KnowledgeManagementPort.CreateDocument command(String text) {
        return new KnowledgeManagementPort.CreateDocument("immutability-test-" + suffix, "Policies", "doc-1", 1,
                DocumentContent.from(text).sha256(), text);
    }
}

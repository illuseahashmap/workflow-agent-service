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

    @Test
    void freezesMultipleSourcesAndRechecksRevokedEvidenceOnReplay() {
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new JdbcTemplate(source);
        var template = new NamedParameterJdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var management = new PostgresKnowledgeManagementAdapter(template);
        var indexes = new PostgresKnowledgeIndexAdapter(template);
        var ingestion = new io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService(
                new PostgresKnowledgeDocumentStore(template), indexes,
                new io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker(128, 16));
        String tenant = "multi-" + suffix;
        String procurement = "procurement-" + suffix;
        String finance = "finance-" + suffix;
        var indexIds = new java.util.ArrayList<Long>();
        for (String scope : java.util.List.of(procurement, finance)) {
            var text = "approval original policy " + scope;
            var doc = transaction.execute(status -> management.createDocument(tenant,
                    new KnowledgeManagementPort.CreateDocument(scope, scope, "policy", 1,
                            DocumentContent.from(text).sha256(), text)));
            var index = transaction.execute(status -> ingestion.ingest(tenant, scope, doc.id()).indexVersion());
            transaction.executeWithoutResult(status -> indexes.activate(index));
            indexIds.add(index.id());
            management.grant(tenant, new KnowledgeManagementPort.GrantScope("reader", scope));
        }
        var bindings = java.util.List.of(
                new io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding(procurement, indexIds.get(0)),
                new io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding(finance, indexIds.get(1)));
        assertThatThrownBy(() -> transaction.execute(status -> management.createProfile(tenant,
                new KnowledgeManagementPort.CreateProfile("incomplete", "KEYWORD", 10, 0, indexIds.getFirst(),
                        java.util.List.of(procurement, finance)))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exactly one frozen source index");
        assertThatThrownBy(() -> transaction.execute(status -> management.createProfile(tenant,
                new KnowledgeManagementPort.CreateProfile("not-vector", "VECTOR", 10, 0, indexIds.getFirst(),
                        java.util.List.of(procurement, finance), bindings))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("compatible embedding recipe");
        var profile = transaction.execute(status -> management.createProfile(tenant,
                new KnowledgeManagementPort.CreateProfile("multi", "KEYWORD", 10, 0, indexIds.getFirst(),
                        java.util.List.of(procurement, finance), bindings)));
        transaction.executeWithoutResult(status -> management.publishProfile(tenant, profile.id()));
        var published = management.profiles(tenant, 1, 20, "multi", "PUBLISHED").records().getFirst();
        assertThat(published.indexBindings()).containsExactlyInAnyOrderElementsOf(bindings);
        assertThat(published.scorePolicy().version()).isEqualTo("postgres-simple-lexical-v1");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM knowledge_profile_index_binding WHERE profile_version_id=?", profile.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_retrieval_profile_version SET minimum_score=0.5 WHERE id=?", profile.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_retrieval_profile_version SET status='DRAFT' WHERE id=?", profile.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String role = "rag_binding_reader_" + suffix;
        jdbc.execute("CREATE ROLE " + role + " NOLOGIN");
        jdbc.execute("GRANT SELECT ON knowledge_profile_index_binding TO " + role);
        Long invisible = transaction.execute(status -> {
            jdbc.execute("SET LOCAL ROLE " + role);
            jdbc.execute("SET LOCAL app.tenant_code='not-this-tenant'");
            return jdbc.queryForObject("SELECT count(*) FROM knowledge_profile_index_binding", Long.class);
        });
        assertThat(invisible).isZero();
        var traces = new PostgresRetrievalTraceRepository(template, new com.fasterxml.jackson.databind.ObjectMapper());
        var retrieval = new io.github.illuseahashmap.knowledge.retrieval.application.KnowledgeRetrievalService(
                () -> { throw new AssertionError("No web context"); },
                new io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever(new PostgresKeywordChunkSearchAdapter(template)),
                new PostgresKnowledgeAccessPolicy(template), traces, new PostgresRetrievalProfileRepository(template),
                (t, agent) -> java.util.Optional.of(profile.id()), new PostgresEvidenceVisibilityAdapter(template));
        var request = new io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest("approval", java.util.List.of(),
                java.util.List.of(), null, 10, io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint.AUTO,
                0, java.util.List.of());
        var result = retrieval.searchForAgent(request, tenant, 1, "reader");
        assertThat(result.evidence()).hasSize(2);
        assertThat(traces.find(tenant, result.retrievalTraceId()).orElseThrow().indexBindings()).containsExactlyElementsOf(
                bindings.stream().sorted(java.util.Comparator.comparing(
                        io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding::sourceCode)).toList());
        var refs = result.evidence().stream().map(e -> e.reference()).toList();
        retrieval.validateResultAccess(tenant, 1, "reader", result.retrievalTraceId(), refs);
        management.revoke(tenant, new KnowledgeManagementPort.GrantScope("reader", finance));
        assertThatThrownBy(() -> retrieval.validateResultAccess(tenant, 1, "reader", result.retrievalTraceId(), refs))
                .hasMessageContaining("no longer authorized");
        assertThat(retrieval.searchForAgent(request, tenant, 1, "reader").evidence()).hasSize(1);
        management.grant(tenant, new KnowledgeManagementPort.GrantScope("reader", finance));
        jdbc.update("UPDATE knowledge_source SET enabled=FALSE WHERE tenant_code=? AND source_code=?", tenant, finance);
        assertThatThrownBy(() -> retrieval.validateResultAccess(tenant, 1, "reader", result.retrievalTraceId(), refs))
                .hasMessageContaining("no longer authorized");
        assertThat(retrieval.searchForAgent(request, tenant, 1, "reader").evidence()).hasSize(1);
        jdbc.update("UPDATE knowledge_source SET enabled=TRUE WHERE tenant_code=? AND source_code=?", tenant, finance);
        var newText = "approval replacement policy";
        var newer = transaction.execute(status -> management.createDocument(tenant,
                new KnowledgeManagementPort.CreateDocument(procurement, procurement, "policy", 2,
                        DocumentContent.from(newText).sha256(), newText)));
        var newIndex = transaction.execute(status -> ingestion.ingest(tenant, procurement, newer.id()).indexVersion());
        transaction.executeWithoutResult(status -> indexes.activate(newIndex));
        assertThat(retrieval.searchForAgent(request, tenant, 1, "reader").evidence())
                .allSatisfy(e -> assertThat(((io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence) e).text())
                        .contains("original"));
        jdbc.update("UPDATE knowledge_document_version SET status='DISABLED' WHERE tenant_code=? AND source_code=? AND version=1",
                tenant, procurement);
        assertThatThrownBy(() -> retrieval.validateResultAccess(tenant, 1, "reader", result.retrievalTraceId(), refs))
                .hasMessageContaining("no longer authorized");
        assertThatThrownBy(() -> transaction.execute(status -> management.createProfile("other-tenant",
                new KnowledgeManagementPort.CreateProfile("forged", "KEYWORD", 10, 0, indexIds.getFirst(),
                        java.util.List.of(procurement, finance), bindings))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void upgradesPublishedV58ProfilesWithoutChangingTheirHistoricalIndexOrPayload() {
        String name = "rag_v58_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
        admin.execute("CREATE DATABASE " + name);
        String authority = java.net.URI.create(databaseUrl.substring(5)).getRawAuthority();
        String url = "jdbc:postgresql://" + authority + "/" + name;
        Flyway.configure().dataSource(url, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform").target("58").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, databaseUser, databasePassword));
        jdbc.update("INSERT INTO knowledge_source(tenant_code,source_code,name) VALUES('default','legacy','Legacy')");
        long index = jdbc.queryForObject("""
                INSERT INTO knowledge_index_version(tenant_code,source_code,version,embedding_model,status)
                VALUES('default','legacy',1,'none','ACTIVE') RETURNING id
                """, Long.class);
        long profile = jdbc.queryForObject("""
                INSERT INTO knowledge_retrieval_profile_version(tenant_code,profile_code,version,strategy,max_results,
                    minimum_score,index_version_id,status) VALUES('default','legacy',1,'KEYWORD',10,0.2,?,'PUBLISHED') RETURNING id
                """, Long.class, index);
        jdbc.update("INSERT INTO knowledge_retrieval_profile_scope(profile_version_id,tenant_code,scope_code) VALUES(?,'default','legacy')",
                profile);
        String original = jdbc.queryForObject("SELECT to_jsonb(p)::text FROM knowledge_retrieval_profile_version p WHERE id=?",
                String.class, profile);
        Flyway.configure().dataSource(url, databaseUser, databasePassword).locations("classpath:db/migration/platform").load().migrate();
        assertThat(jdbc.queryForObject("SELECT (to_jsonb(p)-'score_kind'-'score_version')::text FROM knowledge_retrieval_profile_version p WHERE id=?",
                String.class, profile)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT index_version_id FROM knowledge_profile_index_binding WHERE profile_version_id=?",
                Long.class, profile)).isEqualTo(index);
        assertThat(jdbc.queryForObject("SELECT score_version FROM knowledge_retrieval_profile_version WHERE id=?",
                String.class, profile)).isEqualTo("postgres-simple-lexical-v1");
    }

    private KnowledgeManagementPort.CreateDocument command(String text) {
        return new KnowledgeManagementPort.CreateDocument("immutability-test-" + suffix, "Policies", "doc-1", 1,
                DocumentContent.from(text).sha256(), text);
    }

    @Test
    void tiedMultiSourceKeywordVectorAndHybridTopKUseTheSameStableIdentityOrder() {
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new JdbcTemplate(source);
        var template = new NamedParameterJdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var management = new PostgresKnowledgeManagementAdapter(template);
        var indexes = new PostgresKnowledgeIndexAdapter(template);
        io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort embedding = texts ->
                new io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort.Batch("sort:v1", 2,
                        texts.stream().map(text -> java.util.List.of(1.0f, 0.0f)).toList());
        var ingestion = new io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService(
                new PostgresKnowledgeDocumentStore(template), indexes,
                new io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker(128, 16), embedding);
        String tenant = "sort-" + suffix;
        var scopes = java.util.List.of("zeta-" + suffix, "alpha-" + suffix);
        var bindings = new java.util.ArrayList<io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding>();
        for (String scope : scopes) {
            KnowledgeManagementPort.DocumentView doc = null;
            for (String name : java.util.List.of("second", "first")) {
                doc = transaction.execute(status -> management.createDocument(tenant,
                        new KnowledgeManagementPort.CreateDocument(scope, scope, name, 1,
                                DocumentContent.from("approval policy").sha256(), "approval policy")));
            }
            long docId = doc.id();
            var index = transaction.execute(status -> ingestion.ingest(tenant, scope, docId).indexVersion());
            transaction.executeWithoutResult(status -> indexes.activate(index));
            bindings.add(new io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding(scope, index.id()));
        }
        var expected = jdbc.query("SELECT source_code,chunk_id FROM knowledge_chunk WHERE tenant_code=?",
                (row, n) -> java.util.Map.entry(row.getString(1), row.getString(2)), tenant).stream()
                .sorted(java.util.Map.Entry.<String, String>comparingByKey().thenComparing(java.util.Map.Entry.comparingByValue()))
                .map(java.util.Map.Entry::getValue).toList();
        assertThat(expected).hasSize(4);
        var keyword = new PostgresKeywordChunkSearchAdapter(template);
        var vector = new PostgresVectorSearchAdapter(template);
        var hybrid = new io.github.illuseahashmap.knowledge.retrieval.application.StrategyRetriever(
                new io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever(keyword),
                new io.github.illuseahashmap.knowledge.retrieval.application.VectorRetriever(embedding, vector));
        for (boolean sequential : java.util.List.of(true, false)) {
            transaction.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL enable_seqscan=" + (sequential ? "on" : "off"));
                for (int k : java.util.List.of(1, 2, 4)) {
                    var request = new io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest("approval", scopes,
                            java.util.List.of(), null, k,
                            io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint.HYBRID,
                            0, java.util.List.of(), bindings.getFirst().indexVersionId(), bindings);
                    assertThat(keyword.search(tenant, request, k)).extracting(hit -> hit.chunk().chunkId())
                            .containsExactlyElementsOf(expected.subList(0, k));
                    assertThat(vector.search(tenant, request, java.util.List.of(1.0f, 0.0f), "sort:v1", k))
                            .extracting(hit -> hit.chunk().chunkId()).containsExactlyElementsOf(expected.subList(0, k));
                    assertThat(hybrid.retrieve(tenant, request).evidence()).extracting(evidence -> evidence.reference().locator())
                            .containsExactlyElementsOf(expected.subList(0, k));
                }
            });
        }
    }

    @Test
    void procurementMarkdownRetrievalBaseline() throws Exception {
        Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
                .locations("classpath:db/migration/platform", "classpath:db/migration/vector").load().migrate();
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var jdbc = new NamedParameterJdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var management = new PostgresKnowledgeManagementAdapter(jdbc);
        var indexes = new PostgresKnowledgeIndexAdapter(jdbc);
        var ingestion = new io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService(
                new PostgresKnowledgeDocumentStore(jdbc), indexes,
                new io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker(1200, 120));
        var retriever = new io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever(
                new PostgresKeywordChunkSearchAdapter(jdbc));
        var fixtures = java.nio.file.Path.of("../docs/fixtures/knowledge");
        var pilot = fixtures.resolve("pilots/procurement-approval-v1");
        var policy = pilot.resolve("sources/approval.md");
        String tenant = "pilot-" + suffix;
        String scope = "procurement-pilot";
        Long indexId = null;
        for (var path : java.util.List.of(fixtures.resolve("domains/finance/FIN-POL-01.md"), policy)) {
            var body = DocumentContent.from(java.nio.file.Files.readString(path));
            var doc = tx.execute(status -> management.createDocument(tenant, new KnowledgeManagementPort.CreateDocument(
                    scope, "Pilot", path.getFileName().toString(), 1, body.sha256(), body.text())));
            var index = tx.execute(status -> ingestion.ingest(tenant, scope, doc.id()).indexVersion());
            tx.executeWithoutResult(status -> indexes.activate(index));
            indexId = index.id();
        }
        var lines = java.nio.file.Files.readAllLines(policy);
        var anchors = new java.util.HashMap<String, String>();
        java.nio.file.Files.readAllLines(pilot.resolve("anchors.tsv")).stream().skip(1).forEach(line -> {
            var cells = line.split("\t");
            anchors.put(cells[0], lines.get(Integer.parseInt(cells[1]) - 1));
        });
        assertThat(anchors).hasSize(7);
        assertThat(anchors.values()).allMatch(value -> !value.isBlank());
        var cases = java.nio.file.Files.readAllLines(pilot.resolve("cases.tsv")).stream().skip(1).toList();
        assertThat(cases).hasSize(9);
        for (String line : cases) {
            var cells = line.split("\t");
            var request = new io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest(cells[1],
                    java.util.List.of(scope), java.util.List.of(), null, 3,
                    io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint.KEYWORD,
                    0, java.util.List.of(), indexId);
            var citations = retriever.retrieve(tenant, request).citations();
            int rank = 0;
            for (int i = 0; i < citations.size(); i++) {
                var citation = citations.get(i);
                if (citation.reference().sourceId().equals("approval.md") && citation.reference().sourceVersion().equals("1")
                        && java.util.Arrays.stream(cells[2].split(",")).map(anchors::get)
                        .allMatch(citation.excerpt()::contains)) {
                    rank = i + 1;
                    break;
                }
            }
            System.out.printf("PROC-PILOT %s completeEvidenceRank=%d candidates=%d%n", cells[0], rank, citations.size());
            assertThat(rank).as("All required policy excerpts in Top 3 for %s", cells[0]).isBetween(1, 3);
            assertThat(retriever.retrieve(tenant + "-other", request).citations()).isEmpty();
        }
    }

    @Test
    void nativeProcurementEvidenceRoundTrip() throws Exception {
        Flyway.configure().dataSource(databaseUrl,databaseUser,databasePassword)
                .locations("classpath:db/migration/platform","classpath:db/migration/vector").load().migrate();
        var ds=new DriverManagerDataSource(databaseUrl,databaseUser,databasePassword);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var db=new JdbcTemplate(ds);
        var jdbc=new NamedParameterJdbcTemplate(ds);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var management=new PostgresKnowledgeManagementAdapter(jdbc);
        var originals=new NativeEvidenceStore(jdbc,management,json);
        var indexes=new PostgresKnowledgeIndexAdapter(jdbc);
        var ingestion=new io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService(
                new PostgresKnowledgeDocumentStore(jdbc),indexes,new io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker(1200,120));
        String tenant="native-"+suffix;
        db.update("INSERT INTO workflow_tenant(tenant_id,tenant_code,tenant_name) VALUES(?,?,?)",tenant,tenant,tenant);
        long provider=db.queryForObject("INSERT INTO agent_provider(tenant_code,provider_code,provider_name,provider_type) VALUES(?,'pilot','pilot','MOCK') RETURNING id",Long.class,tenant);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new NativeEvidenceController(
                originals,()->new io.github.illuseahashmap.workflow.shared.context.TenantContext.TenantInfo("1",tenant,tenant),
                ()->new io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal("USER","buyer","buyer","buyer",tenant,java.util.Set.of(),java.util.Set.of())))
                .setControllerAdvice(new io.github.illuseahashmap.workflow.shared.exception.GlobalExceptionHandler()).build();
        var pilot=java.nio.file.Path.of("../docs/fixtures/knowledge/pilots/procurement-approval-v1");
        var gold=java.nio.file.Files.readAllLines(pilot.resolve("anchors.tsv")).stream().skip(1)
                .map(s->s.split("\\t")).collect(java.util.stream.Collectors.toMap(a->a[0],a->a));
        var question=java.nio.file.Files.readAllLines(pilot.resolve("cases.tsv")).stream().filter(s->s.startsWith("B04\t"))
                .findFirst().orElseThrow().split("\\t");
        for(var fmt:java.util.List.of("MD","XLSX","DOCX","PDF")){
            String scope="s-"+fmt, external="approval."+fmt.toLowerCase(java.util.Locale.ROOT);
            byte[] bytes=java.nio.file.Files.readAllBytes(pilot.resolve("sources").resolve(external));
            var upload=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/knowledge/originals")
                    .file(new org.springframework.mock.web.MockMultipartFile("file",external,"application/octet-stream",bytes))
                    .param("sourceCode",scope).param("externalDocumentId",external).param("version","1");
            tx.executeWithoutResult(s->{
                try {mvc.perform(upload).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());}
                catch(Exception e){throw new IllegalStateException(e);}
            });
            var file=originals.coverage(tenant,scope,external,1).original();
            var retry=tx.execute(s->originals.ingest(tenant,scope,external,1,fmt,bytes));
            assertThat(retry).isEqualTo(file);
            var coverage=originals.coverage(tenant,scope,external,1);
            assertThat(coverage.locations()).hasSize(file.blocks());
            assertThat(coverage.original()).isEqualTo(file);
            assertThat(db.queryForObject("SELECT original_bytes FROM knowledge_native_original WHERE tenant_code=? AND source_code=?",
                    byte[].class,tenant,scope)).isEqualTo(bytes);
            if(fmt.equals("DOCX")||fmt.equals("PDF")){
                assertThat(file.status()).isEqualTo("NOT_INDEXED");
                assertThat(file.documentVersionId()).isNull();
                assertThat(db.queryForObject("SELECT count(*) FROM knowledge_ingestion_job WHERE tenant_code=? AND source_code=?",
                        Integer.class,tenant,scope)).isZero();
                continue;
            }
            assertThat(file.blocks()).isGreaterThan(4);
            var changed=java.util.Arrays.copyOf(bytes,bytes.length+1);
            assertThatThrownBy(()->tx.execute(s->originals.ingest(tenant,scope,external,1,fmt,changed))).isInstanceOf(BusinessException.class);
            var index=tx.execute(s->ingestion.ingest(tenant,scope,file.documentVersionId()).indexVersion());
            tx.executeWithoutResult(s->indexes.activate(index));
            var profile=tx.execute(s->management.createProfile(tenant,new KnowledgeManagementPort.CreateProfile(
                    scope,"KEYWORD",16,0,index.id(),java.util.List.of(scope))));
            tx.executeWithoutResult(s->{management.publishProfile(tenant,profile.id());
                management.grant(tenant,new KnowledgeManagementPort.GrantScope("buyer",scope));});
            var retrieval=new io.github.illuseahashmap.knowledge.retrieval.application.KnowledgeRetrievalService(
                    ()->null,new io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever(new PostgresKeywordChunkSearchAdapter(jdbc)),
                    new PostgresKnowledgeAccessPolicy(jdbc),new PostgresRetrievalTraceRepository(jdbc,json),new PostgresRetrievalProfileRepository(jdbc),
                    new PostgresAgentRetrievalProfileBinding(jdbc),new PostgresEvidenceVisibilityAdapter(jdbc));
            var byPosition=new java.util.HashMap<String,String>();
            var excerpts=new java.util.HashMap<String,String>();

            var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beans.registerSingleton("retrieval",retrieval);
            var search=new io.github.illuseahashmap.workflow.agent.KnowledgeSearchAgentTool(
                    beans.getBeanProvider(io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetrievalUseCase.class),json);
            var governance=new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolGovernanceRepository(jdbc);
            var validator=new io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator(json);
            var registry=new io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry(java.util.List.of(search),
                    governance,governance,validator,json,java.util.List.of(),
                    new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolSnapshotRepository(jdbc));
            // Independent oracle: review native positions against the frozen index before any search.
            var chunkIds=db.queryForList("SELECT chunk_id FROM knowledge_chunk WHERE index_version_id=? ORDER BY ordinal",String.class,index.id());
            assertThat(chunkIds).hasSize(coverage.locations().size());
            var reviewed=new java.util.LinkedHashMap<String,String>();
            for(int i=0;i<chunkIds.size();i++) {
                var a=coverage.locations().get(i);
                reviewed.put(fmt.equals("MD")?a.start():a.start()+":"+a.end(),chunkIds.get(i));
            }
            var required=java.util.Arrays.stream(question[2].split(",")).map(name->reviewed.get(gold.get(name)[fmt.equals("MD")?1:3])).toList();
            assertThat(required).doesNotContainNull().doesNotHaveDuplicates().hasSize(4);
            var schema=json.writeValueAsString(java.util.Map.of("type","object","properties",java.util.Map.of(
                    "decision",java.util.Map.of("type","string"),"sources",java.util.Map.of("type","array","items",java.util.Map.of("type","string"))),
                    "x-workflow-citation-claim",java.util.Map.of("version","1","amountField","amount","above",20000,"decisionField","decision",
                            "disposition","CONDITIONAL","citationsField","sources","requiredChunks",required)));
            var versions=new io.github.illuseahashmap.agent.definition.infrastructure.persistence.JdbcAgentDefinitionVersionRepository(jdbc);
            var membership=new PostgresCitationPublicationAdapter(jdbc);
            assertThat(membership.containsAll(tenant+"-other",profile.id(),java.util.Set.copyOf(required))).isFalse();
            if (fmt.equals("XLSX")) {
                String otherChunk=db.queryForObject("SELECT min(chunk_id) FROM knowledge_chunk WHERE tenant_code=? AND source_code='s-MD'",String.class,tenant);
                assertThat(membership.containsAll(tenant,profile.id(),java.util.Set.of(required.getFirst(),otherChunk))).isFalse();
            }
            var publisher=new io.github.illuseahashmap.agent.definition.application.impl.AgentDefinitionServiceImpl(
                    new io.github.illuseahashmap.agent.definition.infrastructure.persistence.JdbcAgentDefinitionRepository(jdbc),versions,
                    new io.github.illuseahashmap.agent.provider.infrastructure.persistence.JdbcAgentProviderRepository(jdbc),
                    ()->new io.github.illuseahashmap.workflow.shared.context.TenantContext.TenantInfo(tenant,tenant,tenant),
                    ()->new io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal("USER","buyer","buyer","buyer",tenant,java.util.Set.of(),java.util.Set.of()),
                    json,validator,registry,membership);
            var definition=tx.execute(s->publisher.create(new io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionCommand(scope,scope,"test",true)));
            long vid=versions.findLatest(tenant,definition.id()).orElseThrow().id();
            java.util.function.BiConsumer<Long,String> configure=(pid,contract)->tx.executeWithoutResult(s->
                    publisher.updateDraft(definition.id(),vid,new io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand(
                            io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode.PLATFORM_AGENT,provider,null,"policy",120,
                            io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy.FAIL_PROCESS,
                            "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}}}",contract,java.util.List.of("knowledge_search"),pid)));
            var draft=tx.execute(s->management.createProfile(tenant,new KnowledgeManagementPort.CreateProfile(scope+"-draft","KEYWORD",16,0,index.id(),java.util.List.of(scope))));
            for (Long invalid:java.util.Arrays.asList(null,draft.id(),profile.id())) {
                configure.accept(invalid, java.util.Objects.equals(invalid,profile.id())?schema.replace(required.getLast(),"outside-profile"):schema);
                assertThatThrownBy(()->tx.execute(s->publisher.publish(definition.id(),vid))).isInstanceOf(BusinessException.class)
                        .hasMessageContaining("published retrieval profile");
                assertThat(versions.findByVersionId(tenant,vid).orElseThrow().published()).isFalse();
            }
            configure.accept(profile.id(),schema);
            tx.execute(s->publisher.publish(definition.id(),vid));
            var version=versions.findByVersionId(tenant,vid).orElseThrow();
            var request=new io.github.illuseahashmap.agent.runtime.application.port.AgentTool.Request(
                    tenant,java.util.Map.of("query",question[1],"maxResults",16),java.time.Duration.ofSeconds(10),
                    "pilot","pilot:1","pilot",vid,"tool:1",vid,"buyer");
            java.util.function.Supplier<String> invoke=()->registry.execute(tenant,version.toolSetJson(),null,"knowledge_search",request).output();
            var toolOutput=invoke.get();
            assertThat(invoke.get()).isEqualTo(toolOutput);
            var result=json.readTree(toolOutput);
            final String trace=result.path("traceId").asText();
            for(var citation:result.path("citations")){
                    var key=citation.path("reference").path("locator").asText();
                    assertThat(citation.has("excerpt")).isFalse();
                    var v=originals.read(tenant,"buyer",trace,key);
                    assertThat(v.originalSha256()).isEqualTo(file.sha256());
                    assertThat(result.path("evidence")).anyMatch(e->citation.path("reference").equals(e.path("reference"))
                            && v.excerpt().equals(e.path("text").asText()));
                    var a=v.anchor();
                    assertThat(a.version()).isEqualTo(2);
                    if(fmt.equals("XLSX"))assertThat(a.part()).isEqualTo("审批矩阵");
                    String pos=fmt.equals("MD") ? a.start() : a.start()+":"+a.end();
                    byPosition.put(pos,key); excerpts.put(key,v.excerpt());
            }
            for(var name:question[2].split(",")){
                String pos=gold.get(name)[fmt.equals("MD")?1:3];
                assertThat(byPosition).as("%s %s %s",fmt,name,pos).containsKey(pos);
                assertThat(byPosition.get(pos)).isEqualTo(reviewed.get(pos));
            }
            assertThat(required).doesNotHaveDuplicates().hasSize(4);
            assertThat(excerpts.get(required.get(2))).contains("直属负责人","部门负责人","必要时","财务中心","复核预算");
            assertThat(excerpts.get(required.get(3))).contains("未完成准入","核验");
            // Only the reviewed conditional disposition can pass, and only with all four cited blocks.
            var policy=new io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy(40000);
            var cmd=new io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor.Command(tenant,version,null,"{\"amount\":20000.01}",null,"trace");
            for(int missing:new int[]{-1,0,1,2,3}){
                var memory=new io.github.illuseahashmap.agent.runtime.application.checkpoint.TaskMemory(cmd,json,policy);
                var delivered=result.deepCopy();
                if(missing!=-1){
                    for(var key:java.util.List.of("citations","evidence")){
                        var entries=(com.fasterxml.jackson.databind.node.ArrayNode)delivered.path(key);
                        for(int i=entries.size()-1;i>=0;i--) if(required.get(missing).equals(
                                entries.get(i).path("reference").path("locator").textValue())) entries.remove(i);
                    }
                }
                String tool=missing==-1 ? toolOutput : json.writeValueAsString(delivered);
                memory.observe(new io.github.illuseahashmap.agent.runtime.application.checkpoint.TaskMemory.Receipt("ref","call","knowledge_search","{}",policy.digest(tool)),tool);
                String conditional=json.writeValueAsString(java.util.Map.of("decision","CONDITIONAL","sources",required));
                String mandatory=json.writeValueAsString(java.util.Map.of("decision","MANDATORY","sources",required));
                assertThatThrownBy(()->memory.verify(mandatory)).isInstanceOf(io.github.illuseahashmap.agent.runtime.application.AgentExecutionException.class);
                if(missing==-1) memory.verify(conditional);
                else assertThatThrownBy(()->memory.verify(conditional)).isInstanceOf(io.github.illuseahashmap.agent.runtime.application.AgentExecutionException.class);
            }
            final String chunk=required.getLast();
            var read=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/knowledge/citations/preview")
                    .param("traceId",trace).param("chunkId",chunk);
            mvc.perform(read).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.originalSha256").value(file.sha256()));
            assertThatThrownBy(()->originals.read(tenant+"-other","buyer",trace,chunk)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->originals.read(tenant,"buyer","not-the-trace",chunk)).isInstanceOf(BusinessException.class);
            db.update("UPDATE knowledge_source SET enabled=false WHERE tenant_code=? AND source_code=?",tenant,scope);
            assertThatThrownBy(()->originals.read(tenant,"buyer",trace,chunk)).isInstanceOf(BusinessException.class);
            db.update("UPDATE knowledge_source SET enabled=true WHERE tenant_code=? AND source_code=?",tenant,scope);
            tx.executeWithoutResult(s->management.revoke(tenant,new KnowledgeManagementPort.GrantScope("buyer",scope)));
            assertThatThrownBy(invoke::get).isInstanceOfSatisfying(io.github.illuseahashmap.agent.runtime.application.AgentExecutionException.class,
                    error->assertThat(error.failure().errorCode()).isEqualTo("KNOWLEDGE_RESULT_ACCESS_REVOKED"));
            mvc.perform(read).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            assertThatThrownBy(()->originals.read(tenant,"buyer",trace,chunk)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void cancelsSlowJdbcReadsWithinTheExecutionBudgetWithoutLeakingTimeoutToReuse() throws Exception {
        var source = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        var guarded = (javax.sql.DataSource) new io.github.illuseahashmap.workflow.config.TenantRlsDataSourcePostProcessor()
                .postProcessAfterInitialization(source, "dataSource");
        try (var connection = guarded.getConnection(); var statement = connection.prepareStatement("SELECT pg_sleep(2)")) {
            statement.setQueryTimeout(10);
            try (var budget = io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline
                    .open(java.time.Duration.ofMillis(200), System::nanoTime)) {
                assertThatThrownBy(statement::execute).isInstanceOfSatisfying(java.sql.SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("57014"));
            }
            assertThat(statement.getQueryTimeout()).isEqualTo(10);
            try (var check = connection.prepareStatement("SELECT 1")) {
                assertThat(check.executeQuery().next()).isTrue();
            }
        }
    }
}

package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.provider.infrastructure.openai.LangChain4jOpenAiModelProviderAdapter;
import io.github.illuseahashmap.agent.provider.infrastructure.openai.ProviderEndpointValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.impl.PlatformAgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.KnowledgeRetrievalService;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetrievalUseCase;
import io.github.illuseahashmap.knowledge.retrieval.domain.QueryExpansionPolicy;
import io.github.illuseahashmap.workflow.agent.KnowledgeSearchAgentTool;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public development-only fixture. Real model execution is explicitly opt-in. */
class QueryExpansionAcceptanceTest extends KnowledgeDatabaseFixture {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private static final Path INPUT = Path.of("../docs/fixtures/knowledge/query-expansion-dev");

    @Test
    void frozenPolicyCompatibilityTraceAndReplay() throws Exception {
        acceptance(false);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "workflow.rag.live-expansion",
            matches = "true")
    void liveDevelopmentAnswersAndCitations() throws Exception {
        acceptance(true);
    }

    @Test
    void statementTimeoutDoesNotMutateSharedTemplate() {
        var jdbc =
                new NamedParameterJdbcTemplate(
                        new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
        int before = jdbc.getJdbcTemplate().getQueryTimeout();
        try (var ignored =
                io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline.open(
                        Duration.ofMillis(100), System::nanoTime)) {
            assertThatThrownBy(
                            () ->
                                    DeadlineQuery.query(
                                            jdbc,
                                            "SELECT pg_sleep(10)",
                                            new org.springframework.jdbc.core.namedparam
                                                    .MapSqlParameterSource(),
                                            (row, number) -> 1))
                    .isInstanceOf(
                            io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline
                                    .Exceeded.class);
        }
        assertThat(jdbc.getJdbcTemplate().getQueryTimeout()).isEqualTo(before);
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    private Map<String, Object> reportTrace(
            io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace trace) {
        return Map.of(
                "traceId",
                trace.traceId(),
                "createdAt",
                trace.createdAt().toString(),
                "profileVersionId",
                trace.profileVersionId(),
                "queryFingerprint",
                trace.queryFingerprint(),
                "authorizedScopes",
                trace.authorizedScopes(),
                "queryExpansion",
                trace.queryExpansion(),
                "elapsedMillis",
                trace.elapsedMillis(),
                "evidenceReferences",
                trace.evidenceReferences());
    }

    private void acceptance(boolean live) throws Exception {
        withScratchDatabase(
                url -> {
                    Flyway.configure()
                            .dataSource(url, databaseUser, databasePassword)
                            .locations(
                                    "classpath:db/migration/platform",
                                    "classpath:db/migration/vector")
                            .load()
                            .migrate();
                    var ds = new DriverManagerDataSource(url, databaseUser, databasePassword);
                    var jdbc = new NamedParameterJdbcTemplate(ds);
                    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
                    var management = new PostgresKnowledgeManagementAdapter(jdbc);
                    var store = new NativeEvidenceStore(jdbc, management, json);
                    var indexes = new PostgresKnowledgeIndexAdapter(jdbc);
                    var bytes =
                            Files.readString(INPUT.resolve("employee-handbook.md"))
                                    .replace("\r\n", "\n")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    var fixture = json.readTree(Files.readString(INPUT.resolve("cases.json")));
                    assertThat(
                                    java.util.HexFormat.of()
                                            .formatHex(
                                                    java.security.MessageDigest.getInstance(
                                                                    "SHA-256")
                                                            .digest(bytes)))
                            .isEqualTo(fixture.path("sourceSha256").asText());
                    var index =
                            tx.execute(
                                    s -> {
                                        var doc =
                                                store.ingest(
                                                        "default",
                                                        "office-policy",
                                                        "employee-handbook.md",
                                                        1,
                                                        "MD",
                                                        bytes);
                                        var ingestion =
                                                new DocumentIngestionService(
                                                        new PostgresKnowledgeDocumentStore(jdbc),
                                                        indexes,
                                                        new TextChunker(1200, 120));
                                        var result =
                                                ingestion.ingest(
                                                        "default",
                                                        "office-policy",
                                                        doc.documentVersionId());
                                        indexes.activate(result.indexVersion());
                                        return result.indexVersion().id();
                                    });
                    var profile =
                            tx.execute(
                                    s ->
                                            management.createProfile(
                                                    "default",
                                                    new KnowledgeManagementPort.CreateProfile(
                                                            "expanded",
                                                            "KEYWORD",
                                                            5,
                                                            0,
                                                            index,
                                                            List.of("office-policy"),
                                                            List.of(),
                                                            QueryExpansionPolicy.RRF_V1)));
                    var old =
                            tx.execute(
                                    s ->
                                            management.createProfile(
                                                    "default",
                                                    new KnowledgeManagementPort.CreateProfile(
                                                            "legacy",
                                                            "KEYWORD",
                                                            5,
                                                            0,
                                                            index,
                                                            List.of("office-policy"))));
                    tx.executeWithoutResult(
                            s -> {
                                management.publishProfile("default", profile.id());
                                management.publishProfile("default", old.id());
                                management.grant(
                                        "default",
                                        new KnowledgeManagementPort.GrantScope(
                                                "reviewer", "office-policy"));
                            });
                    assertThatThrownBy(
                                    () ->
                                            jdbc.update(
                                                    "UPDATE knowledge_retrieval_profile_version SET"
                                                        + " query_expansion_policy='DISABLED' WHERE"
                                                        + " id=:id",
                                                    Map.of("id", profile.id())))
                            .isInstanceOf(
                                    org.springframework.dao.DataIntegrityViolationException.class);
                    var profiles = new PostgresRetrievalProfileRepository(jdbc);
                    assertThat(
                                    profiles.findPublished("default", old.id())
                                            .orElseThrow()
                                            .queryExpansionPolicy())
                            .isEqualTo(QueryExpansionPolicy.DISABLED);
                    assertThat(
                                    management
                                            .profiles("default", 1, 20, "expanded", null)
                                            .records()
                                            .getFirst()
                                            .queryExpansionPolicy())
                            .isEqualTo(QueryExpansionPolicy.RRF_V1);
                    var traces = new PostgresRetrievalTraceRepository(jdbc, json);
                    var calls = new ArrayList<String>();
                    var keyword = new KeywordRetriever(new PostgresKeywordChunkSearchAdapter(jdbc));
                    var useCase =
                            new KnowledgeRetrievalService(
                                    () -> null,
                                    (tenant, request) -> {
                                        calls.add(request.query());
                                        return keyword.retrieve(tenant, request);
                                    },
                                    new PostgresKnowledgeAccessPolicy(jdbc),
                                    traces,
                                    profiles,
                                    (tenant, version) ->
                                            "default".equals(tenant)
                                                    ? Optional.of(
                                                            version == 1 ? profile.id() : old.id())
                                                    : Optional.empty(),
                                    new PostgresEvidenceVisibilityAdapter(jdbc));
                    var beans = new DefaultListableBeanFactory();
                    beans.registerSingleton("retrieval", useCase);
                    var tool =
                            new KnowledgeSearchAgentTool(
                                    beans.getBeanProvider(KnowledgeRetrievalUseCase.class), json);
                    var toolOutputs = new ArrayList<String>();
                    var toolArguments = new ArrayList<Map<String, Object>>();
                    AgentTool observing =
                            new AgentTool() {
                                public String name() {
                                    return tool.name();
                                }

                                public String inputSchema() {
                                    return tool.inputSchema();
                                }

                                public Result execute(Request request) {
                                    toolArguments.add(request.arguments());
                                    var result = tool.execute(request);
                                    toolOutputs.add(result.output());
                                    return result;
                                }

                                public void validateResultAccess(
                                        ResultAccessContext context, String output) {
                                    tool.validateResultAccess(context, output);
                                }
                            };
                    var governance =
                            new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                    .JdbcAgentToolGovernanceRepository(jdbc);
                    var registry =
                            new AgentToolRegistry(
                                    List.of(observing),
                                    governance,
                                    governance,
                                    new AgentOutputSchemaValidator(json),
                                    json);
                    String toolSet = "[\"knowledge_search\"]";
                    var records = new ArrayList<Map<String, Object>>();
                    for (var test : fixture.path("cases")) {
                        String query = test.path("query").asText();
                        if (!live) {
                            var arguments =
                                    Map.<String, Object>of(
                                            "query",
                                            query,
                                            "auxiliaryQueries",
                                            List.of("普通办公采购 金额档位 审批权限"));
                            var legacy =
                                    new AgentTool.Request(
                                            "default",
                                            arguments,
                                            Duration.ofSeconds(10),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            null,
                                            0,
                                            "tool:1",
                                            2L,
                                            "reviewer");
                            int before = calls.size();
                            registry.execute("default", toolSet, null, tool.name(), legacy);
                            assertThat(calls.size() - before).isEqualTo(1);
                            var unsafeArguments =
                                    Map.<String, Object>of(
                                            "query",
                                            query,
                                            "auxiliaryQueries",
                                            List.of("采购金额以下 无需联合审批"));
                            var unsafe =
                                    new AgentTool.Request(
                                            "default",
                                            unsafeArguments,
                                            Duration.ofSeconds(10),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            null,
                                            0,
                                            "tool:unsafe",
                                            1L,
                                            "reviewer");
                            before = calls.size();
                            var fallback =
                                    registry.execute("default", toolSet, null, tool.name(), unsafe);
                            assertThat(calls.size() - before).isEqualTo(1);
                            var fallbackTrace =
                                    traces.find(
                                                    "default",
                                                    json.readTree(fallback.output())
                                                            .path("traceId")
                                                            .asText())
                                            .orElseThrow();
                            assertThat(fallbackTrace.queryExpansion().outcome())
                                    .isEqualTo("AUXILIARY_REJECTED_CONDITIONS_TOPIC_V1");
                            var request =
                                    new AgentTool.Request(
                                            "default",
                                            arguments,
                                            Duration.ofSeconds(10),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            null,
                                            0,
                                            "tool:1",
                                            1L,
                                            "reviewer");
                            before = calls.size();
                            var first =
                                    registry.execute(
                                            "default", toolSet, null, tool.name(), request);
                            assertThat(calls.size() - before).isEqualTo(2);
                            var again =
                                    registry.execute(
                                            "default", toolSet, null, tool.name(), request);
                            assertThat(again.output()).isEqualTo(first.output());
                            assertThat(calls.size() - before).isEqualTo(2);
                            var trace =
                                    traces.find(
                                                    "default",
                                                    json.readTree(first.output())
                                                            .path("traceId")
                                                            .asText())
                                            .orElseThrow();
                            assertThat(trace.queryExpansion().outcome()).isEqualTo("FUSED");
                            assertThat(trace.queryExpansion().lanes()).hasSize(2);
                            var delivered = json.readTree(first.output());
                            assertThat(delivered.path("evidence")).isNotEmpty();
                            assertThat(delivered.path("evidence").get(0).path("text").asText())
                                    .contains(test.path("requiredQuote").asText());
                            records.add(
                                    Map.of(
                                            "caseId",
                                            test.path("id").asText(),
                                            "query",
                                            query,
                                            "toolResult",
                                            delivered,
                                            "trace",
                                            reportTrace(trace),
                                            "legacyCalls",
                                            1,
                                            "expandedCalls",
                                            2,
                                            "replayAdditionalCalls",
                                            0));
                            management.revoke(
                                    "default",
                                    new KnowledgeManagementPort.GrantScope(
                                            "reviewer", "office-policy"));
                            assertThatThrownBy(
                                            () ->
                                                    registry.execute(
                                                            "default",
                                                            toolSet,
                                                            null,
                                                            tool.name(),
                                                            request))
                                    .isInstanceOf(
                                            io.github.illuseahashmap.agent.runtime.application
                                                    .AgentExecutionException.class);
                            management.grant(
                                    "default",
                                    new KnowledgeManagementPort.GrantScope(
                                            "reviewer", "office-policy"));
                            continue;
                        }
                        toolOutputs.clear();
                        toolArguments.clear();
                        String model =
                                java.util.Objects.requireNonNull(
                                        System.getenv("WORKFLOW_TEST_ARK_CHAT_MODEL"));
                        var now = OffsetDateTime.now();
                        var provider =
                                new AgentProvider(
                                        1L,
                                        "default",
                                        "ark",
                                        "ark",
                                        AgentProviderType.OPENAI_COMPATIBLE,
                                        "https://ark.cn-beijing.volces.com/api/v3/responses",
                                        model,
                                        true,
                                        true,
                                        null,
                                        now,
                                        now);
                        String prompt =
                                "你是只读制度核查助手。必须调用 knowledge_search，query"
                                    + " 逐字使用当前用户问题，不附加指令、解释或括号说明。当前检索配置允许一个"
                                    + " auxiliaryQueries，仅生成主题或制度类别词，不包含数字、日期、比较关系、范围、否定或适用条件；所有事实条件保留在"
                                    + " query，不确定则省略辅助查询。只能根据返回的原文证据回答，金额条件严格区分包含边界与不包含边界。输出"
                                    + " answer、requiredApprovers（仅列本次必需审批人）、sources（支撑本次结论的"
                                    + " reference.locator 原值数组）。";
                        String schema =
                                """
                                {"type":"object","required":["answer","requiredApprovers","sources"],"properties":{
                                "answer":{"type":"string"},"requiredApprovers":{"type":"array","items":{"type":"string"}},
                                "sources":{"type":"array","minItems":1,"items":{"type":"string"}}}}
                                """;
                        var version =
                                new AgentDefinitionVersion(
                                        1L,
                                        "default",
                                        1L,
                                        1,
                                        AgentVersionStatus.PUBLISHED,
                                        AgentExecutionMode.PLATFORM_AGENT,
                                        1L,
                                        model,
                                        prompt,
                                        120,
                                        AgentFailurePolicy.FAIL_PROCESS,
                                        null,
                                        schema,
                                        "reviewer",
                                        "reviewer",
                                        now,
                                        now,
                                        now,
                                        toolSet);
                        var adapter =
                                new LangChain4jOpenAiModelProviderAdapter(
                                        json,
                                        new ProviderEndpointValidator(false, false),
                                        1048576) {
                                    @Override
                                    public io.github.illuseahashmap.agent.provider.application.port
                                                    .ModelProviderResponse
                                            invoke(
                                                    io.github.illuseahashmap.agent.provider
                                                                    .application.port
                                                                    .ModelProviderRequest
                                                            request) {
                                        var response = super.invoke(request);
                                        try {
                                            var raw =
                                                    Path.of(
                                                            System.getProperty(
                                                                            "workflow.rag.expansion-report",
                                                                            "target/query-expansion-live.json")
                                                                    + ".responses.jsonl");
                                            Files.createDirectories(
                                                    raw.toAbsolutePath().getParent());
                                            Files.writeString(
                                                    raw,
                                                    json.writeValueAsString(
                                                                    Map.of(
                                                                            "caseId",
                                                                            test.path("id")
                                                                                    .asText(),
                                                                            "response",
                                                                            response))
                                                            + "\n",
                                                    java.nio.file.StandardOpenOption.CREATE,
                                                    java.nio.file.StandardOpenOption.APPEND);
                                        } catch (java.io.IOException failure) {
                                            throw new IllegalStateException(
                                                    "Cannot preserve acceptance response", failure);
                                        }
                                        return response;
                                    }
                                };
                        var executor =
                                new PlatformAgentExecutor(
                                        (t, id) -> System.getenv("ARK_API_KEY"),
                                        new ModelProviderRegistry(List.of(adapter)),
                                        new AgentOutputSchemaValidator(json),
                                        registry,
                                        json,
                                        4);
                        var result =
                                executor.execute(
                                        new AgentExecutor.Command(
                                                "default",
                                                version,
                                                provider,
                                                query,
                                                Duration.ofSeconds(120),
                                                UUID.randomUUID().toString(),
                                                0,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "reviewer"));
                        var answer = json.readTree(result.modelResponse().content());
                        assertThat(answer.path("requiredApprovers"))
                                .isEqualTo(test.path("requiredApprovers"));
                        assertThat(answer.path("answer").asText()).contains("直属负责人");
                        assertThat(toolArguments)
                                .isNotEmpty()
                                .allSatisfy(
                                        args ->
                                                assertThat(
                                                                args.get("query")
                                                                        .toString()
                                                                        .replaceFirst("[？?]$", ""))
                                                        .isEqualTo(
                                                                query.replaceFirst("[？?]$", "")));
                        assertThat(answer.path("sources")).isNotEmpty();
                        var evidenceByLocator = new java.util.HashMap<String, String>();
                        var audited = new ArrayList<Object>();
                        for (String output : toolOutputs) {
                            var body = json.readTree(output);
                            for (var e : body.path("evidence")) {
                                assertThat(e.path("reference").path("sourceId").asText())
                                        .isNotBlank();
                                evidenceByLocator.put(
                                        e.path("reference").path("locator").asText(),
                                        e.path("text").asText());
                            }
                            var trace =
                                    traces.find("default", body.path("traceId").asText())
                                            .orElseThrow();
                            var arguments = toolArguments.get(toolOutputs.indexOf(output));
                            if (arguments.get("auxiliaryQueries") instanceof List<?> auxiliary
                                    && !auxiliary.isEmpty()
                                    && !io.github.illuseahashmap.knowledge.retrieval.domain
                                            .AuxiliaryQueryPolicy.isTopicOnly(
                                            auxiliary.getFirst().toString())) {
                                assertThat(trace.queryExpansion().outcome())
                                        .isEqualTo("AUXILIARY_REJECTED_CONDITIONS_TOPIC_V1");
                                assertThat(trace.queryExpansion().lanes()).hasSize(1);
                            }
                            audited.add(reportTrace(trace));
                        }
                        for (var source : answer.path("sources")) {
                            assertThat(evidenceByLocator).containsKey(source.asText());
                            assertThat(evidenceByLocator.get(source.asText()))
                                    .contains(test.path("requiredQuote").asText());
                        }
                        records.add(
                                Map.of(
                                        "caseId",
                                        test.path("id").asText(),
                                        "model",
                                        model,
                                        "answer",
                                        answer,
                                        "arguments",
                                        List.copyOf(toolArguments),
                                        "toolOutputs",
                                        List.copyOf(toolOutputs),
                                        "retrievalTraces",
                                        audited,
                                        "modelResponse",
                                        result.modelResponse(),
                                        "steps",
                                        result.steps()));
                        System.out.println(
                                "DEV live verified " + test.path("id").asText() + ": " + answer);
                        var report =
                                Path.of(
                                        System.getProperty(
                                                "workflow.rag.expansion-report",
                                                "target/query-expansion-live.json"));
                        Files.createDirectories(report.toAbsolutePath().getParent());
                        Files.writeString(
                                report,
                                json.writerWithDefaultPrettyPrinter().writeValueAsString(records));
                    }
                    if (!live) {
                        Files.createDirectories(Path.of("target"));
                        Files.writeString(
                                Path.of("target/query-expansion-retrieval.json"),
                                json.writerWithDefaultPrettyPrinter().writeValueAsString(records));
                    }
                });
    }
}

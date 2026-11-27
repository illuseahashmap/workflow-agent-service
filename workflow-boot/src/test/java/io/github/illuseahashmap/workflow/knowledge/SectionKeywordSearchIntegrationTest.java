package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

class SectionKeywordSearchIntegrationTest extends KnowledgeDatabaseFixture {
    @Test
    void headingsLocateBodiesWithoutCrossingSectionsScopesOrTheResultBudget() throws Exception {
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
                    var store =
                            new NativeEvidenceStore(
                                    jdbc,
                                    new PostgresKnowledgeManagementAdapter(jdbc),
                                    new ObjectMapper());
                    var indexes = new PostgresKnowledgeIndexAdapter(jdbc);
                    var ingestion =
                            new DocumentIngestionService(
                                    new PostgresKnowledgeDocumentStore(jdbc),
                                    indexes,
                                    new TextChunker(1200, 120));
                    var optical = "## 光谱采集校验\n\n先记录暗场读数。\n\n同时保存环境温度。\n\n## 其他事项\n\n隔壁段落不能随意借用。\n";
                    var thermal = "## 温场记录核对\n\n保留探头位置坐标。\n\n另附采样间隔。\n";
                    for (var spec :
                            List.of(
                                    new String[] {"public", "optical", optical},
                                    new String[] {"public", "thermal", thermal},
                                    new String[] {
                                        "private", "secret", "## 光谱采集校验\n\n禁止返回的内部材料。\n"
                                    })) {
                        tx.execute(
                                s -> {
                                    var d =
                                            store.ingest(
                                                    "default",
                                                    spec[0],
                                                    spec[1],
                                                    1,
                                                    "MD",
                                                    spec[2].getBytes(StandardCharsets.UTF_8));
                                    var r =
                                            ingestion.ingest(
                                                    "default", spec[0], d.documentVersionId());
                                    indexes.activate(r.indexVersion());
                                    return null;
                                });
                    }
                    var adapter = new PostgresKeywordChunkSearchAdapter(jdbc);
                    var single =
                            new RetrievalRequest(
                                    "光谱采集校验",
                                    List.of("public"),
                                    List.of(),
                                    null,
                                    4,
                                    RetrievalRequest.StrategyHint.KEYWORD,
                                    0,
                                    List.of());
                    var hits = adapter.search("default", single, 4);
                    assertThat(hits)
                            .extracting(h -> h.chunk().text())
                            .containsExactly("先记录暗场读数。", "同时保存环境温度。");
                    var both =
                            new RetrievalRequest(
                                    "光谱采集校验与温场记录核对",
                                    List.of("public"),
                                    List.of(),
                                    null,
                                    4,
                                    RetrievalRequest.StrategyHint.KEYWORD,
                                    0,
                                    List.of());
                    var combined = adapter.search("default", both, 4);
                    assertThat(combined)
                            .hasSize(4)
                            .allMatch(h -> !h.chunk().text().startsWith("#"));
                    assertThat(combined)
                            .extracting(h -> h.chunk().text())
                            .containsExactlyInAnyOrder(
                                    "先记录暗场读数。", "同时保存环境温度。", "保留探头位置坐标。", "另附采样间隔。");
                    assertThat(adapter.search("default", both, 2))
                            .hasSize(2)
                            .extracting(h -> h.chunk().documentVersion().externalDocumentId())
                            .containsExactlyInAnyOrder("optical", "thermal");
                    assertThat(adapter.search("other-tenant", both, 4)).isEmpty();
                });
    }
}

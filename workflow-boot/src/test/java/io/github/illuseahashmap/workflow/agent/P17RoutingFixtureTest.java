package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.XmlAgentTaskBindingParser;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.delegate.ExecutionListener;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the actual fixture gateway. The receive-task callback is a test double, not an AgentRun. */
class P17RoutingFixtureTest {
    private static final Path BPMN=Path.of("../docs/fixtures/knowledge/pilots/p17-joint-v1/order-readiness.bpmn20.xml");

    @Test void publishedShapeHasOneAgentWaitAndManualReviewEscape() throws Exception {
        assertThat(new XmlAgentTaskBindingParser(new ObjectMapper()).parse(Files.readString(BPMN)))
                .singleElement().satisfies(binding->assertThat(binding.agentVersionId()).isEqualTo(1L));
    }

    @Test void handoffAndHistoryUseRealFlowableGateways() throws Exception {
        withDatabase(P17RoutingFixtureTest::exercise);
    }

    @FunctionalInterface interface DatabaseTrial { void run(String url,String user,String password) throws Exception; }
    static void withDatabase(DatabaseTrial trial) throws Exception {
        var url=System.getenv("WORKFLOW_TEST_DATABASE_URL");
        if(url!=null && !url.isBlank()){
            var user=System.getenv("WORKFLOW_TEST_DATABASE_USER");
            var password=System.getenv("WORKFLOW_TEST_DATABASE_PASSWORD");
            var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
            var db="p17_"+UUID.randomUUID().toString().replace("-","");
            admin.execute("CREATE DATABASE "+db);
            try { trial.run("jdbc:postgresql://"+java.net.URI.create(url.substring(5)).getRawAuthority()+"/"+db,user,password); }
            finally { admin.execute("DROP DATABASE "+db); }
        } else {
            org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),
                    "Docker or isolated test PostgreSQL required");
            try(var postgres=new PostgreSQLContainer<>("postgres:17-alpine")){
                postgres.start();trial.run(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            }
        }
    }

    private static void exercise(String url,String user,String password) throws Exception {
        var cfg=ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration();
        cfg.setJdbcDriver("org.postgresql.Driver").setJdbcUrl(url).setJdbcUsername(user).setJdbcPassword(password)
                .setDatabaseSchemaUpdate("true").setAsyncExecutorActivate(false);
        cfg.setBeans(Map.of("agentTaskExecutionListener",(ExecutionListener)e->{}));
        var engine=cfg.buildProcessEngine();
        try(var stream=Files.newInputStream(BPMN)){
            engine.getRepositoryService().createDeployment().addInputStream(BPMN.getFileName().toString(),stream).deploy();
            var runtime=engine.getRuntimeService();
            record Sample(boolean review,String action,String decision,String expected){}
            for(var sample:java.util.List.of(
                    new Sample(false,"ANSWER","ORDER_READY","READY_CHECK"),
                    new Sample(false,"ANSWER","ORDER_NOT_READY","NEED_RECORDS"),
                    new Sample(false,"ABSTAIN","MISSING_LIVE_RECORDS","NEED_RECORDS"),
                    new Sample(false,"HUMAN_REVIEW","LIVE_TIMEOUT","MANUAL_REVIEW"),
                    new Sample(true,"ANSWER","ORDER_READY","MANUAL_REVIEW"),
                    new Sample(false,"ABSTAIN","ORDER_READY","MANUAL_REVIEW"),
                    new Sample(false,null,null,"MANUAL_REVIEW"))){
                var instance=runtime.startProcessInstanceByKey("p17_order_readiness_v1").getId();
                var wait=runtime.createExecutionQuery().processInstanceId(instance).activityId("check_order").singleResult();
                var vars=new java.util.HashMap<String,Object>();
                vars.put("agentReviewRequired",sample.review());
                if(sample.action()!=null) {vars.put("checkAction",sample.action());vars.put("checkDecision",sample.decision());}
                runtime.trigger(wait.getId(),vars);
                assertThat(engine.getTaskService().createTaskQuery().processInstanceId(instance).list())
                        .extracting(t->t.getTaskDefinitionKey()).containsExactly(sample.expected());
                assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery()
                        .processInstanceId(instance).activityType("userTask").list())
                        .extracting(h->h.getActivityId()).containsExactly(sample.expected());
            }
        } finally { engine.close(); }
    }
}

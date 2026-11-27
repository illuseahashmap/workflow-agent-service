package io.github.illuseahashmap.workflow.integration;

import io.github.illuseahashmap.agent.definition.application.AgentDefinitionService;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionView;
import io.github.illuseahashmap.agent.runtime.application.AgentRunQueryService;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.WorkflowRuntimeService;
import io.github.illuseahashmap.workflow.process.application.dto.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantAssignment;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessParticipantRequirementsRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in live acceptance. Uses application services; never rewrites a published version in place. */
@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@ActiveProfiles({"local", "vector"})
@EnabledIfEnvironmentVariable(named = "WORKFLOW_RUN_LOCAL_RAG_AGENT_ACCEPTANCE", matches = "true")
class LocalRagAgentFlowAcceptanceTest {
    private static final String TENANT = "default";
    private static final String PROCESS = "purchase_rag_inclusive_process";

    @Autowired KnowledgeManagementPort management;
    @Autowired AgentDefinitionService agents;
    @Autowired WorkflowDefinitionService definitions;
    @Autowired WorkflowRuntimeService runtime;
    @Autowired AgentRunQueryService runs;
    @Autowired RepositoryService repository;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setContext() {
        String adminId = TrustedDataAccessContext.runAsAuthentication(() -> jdbc.queryForObject("""
                SELECT user_id FROM auth_user WHERE tenant_code = :tenant AND username = :username AND enabled = 1
                """, Map.of("tenant", TENANT, "username", "admin"), String.class));
        TenantContext.set(new TenantContext.TenantInfo(TENANT, TENANT, "默认租户"));
        CurrentPrincipalContext.set(new CurrentPrincipal("USER", adminId, "admin", "平台管理员",
                TENANT, Set.of("PLATFORM_ADMIN"), Set.of()));
    }

    @AfterEach
    void clearContext() {
        CurrentPrincipalContext.clear();
        TenantContext.clear();
    }

    @Test
    void publishedVectorProfilePowersAgentRunAndAdvancesFlowable() throws Exception {
        long indexId = TrustedDataAccessContext.runAsSystemWorker(() -> management.indexVersions(TENANT, "ACTIVE")
                .stream().filter(index -> "company-handbook".equals(index.sourceCode())
                        && !"none".equals(index.embeddingModel())).findFirst().orElseThrow().id());
        long profileId = TrustedDataAccessContext.runAsSystemWorker(() -> management
                .profiles(TENANT, 1, 100, "purchase-policy", "PUBLISHED").records().stream()
                .filter(profile -> profile.indexVersionId() == indexId && "HYBRID".equals(profile.strategy()))
                .findFirst().map(KnowledgeManagementPort.ProfileView::id)
                .orElseGet(() -> {
                    var draft = management.createProfile(TENANT, new KnowledgeManagementPort.CreateProfile(
                            "purchase-policy", "HYBRID", 5, 0, indexId, List.of("company-handbook")));
                    return management.publishProfile(TENANT, draft.id()).id();
                }));

        long definitionId = TrustedDataAccessContext.runAsSystemWorker(() -> agents.page(1, 100,
                "text_rag", true).records().stream().filter(definition -> "text_rag".equals(definition.code()))
                .findFirst().orElseThrow().id());
        AgentVersionView agentVersion = TrustedDataAccessContext.runAsSystemWorker(() -> {
            var versions = agents.versions(definitionId);
            return versions.stream().filter(version -> version.retrievalProfileVersionId() != null
                    && version.retrievalProfileVersionId() == profileId && version.status().name().equals("PUBLISHED"))
                    .findFirst().orElseGet(() -> {
                        AgentVersionView source = versions.stream().filter(version -> version.status().name()
                                .equals("PUBLISHED")).findFirst().orElseThrow();
                        AgentVersionView draft = agents.createDraft(definitionId);
                        agents.updateDraft(definitionId, draft.id(), new AgentVersionCommand(source.executionMode(),
                                source.providerId(), source.modelName(), source.systemPrompt(),
                                source.timeoutSeconds(), source.failurePolicy(), source.inputSchema(),
                                source.outputSchema(), source.toolCodes(), profileId));
                        return agents.publish(definitionId, draft.id());
                    });
        });

        var active = TrustedDataAccessContext.runAsSystemWorker(() -> definitions.getActiveVersion(PROCESS));
        String xml = TrustedDataAccessContext.runAsSystemWorker(() -> {
            var source = repository.createProcessDefinitionQuery()
                    .processDefinitionId(active.processDefinitionId()).singleResult();
            try (var stream = repository.getResourceAsStream(source.getDeploymentId(), source.getResourceName())) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot read active BPMN", exception);
            }
        });
        if (!xml.contains("agentVersionId=\"" + agentVersion.id() + "\"")) {
            String updated = xml.replaceFirst("agentVersionId=\"[0-9]+\"",
                    "agentVersionId=\"" + agentVersion.id() + "\"");
            assertThat(updated).isNotEqualTo(xml);
            var deployed = TrustedDataAccessContext.runAsSystemWorker(() -> definitions.deploy(
                    new DeployProcessRequest(PROCESS, "采购审批（RAG 包容网关）", updated)));
            TrustedDataAccessContext.runAsSystemWorker(() -> definitions.activate(
                    new ActivateProcessVersionRequest(PROCESS, deployed.version())));
        }

        Map<String, Object> variables = Map.of("purchaseAmount", 1500, "purchaseType", "普通办公采购",
                "purchaseDescription", "采购办公键盘", "policyQuestion", "普通办公采购1500元应由谁审批？");
        var requirements = TrustedDataAccessContext.runAsSystemWorker(() -> runtime.getStartParticipantRequirements(
                new ProcessParticipantRequirementsRequest(PROCESS, null, variables)));
        var assignments = requirements.stream().filter(requirement -> requirement.required())
                .map(requirement -> new ParticipantAssignment(requirement.activityId(), List.of("admin"))).toList();
        var started = TrustedDataAccessContext.runAsSystemWorker(() -> runtime.start(new StartProcessRequest(
                PROCESS, null, "rag-vector-acceptance-" + System.currentTimeMillis(), variables, assignments)));
        assertThat(started.processInstanceId()).isNotBlank();

        long end = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < end) {
            var found = TrustedDataAccessContext.runAsSystemWorker(
                    () -> runs.findByProcessInstance(started.processInstanceId()));
            if (!found.isEmpty() && found.getFirst().status().isTerminal()) {
                assertThat(found.getFirst().status()).isEqualTo(AgentRunStatus.SUCCEEDED);
                assertThat(found.getFirst().agentVersion()).isEqualTo(agentVersion.version());
                var process = TrustedDataAccessContext.runAsSystemWorker(
                        () -> runtime.getProcessStatus(started.processInstanceId()));
                assertThat(process.activeTasks()).isNotEmpty();
                assertThat(process.activeTasks()).anySatisfy(task -> assertThat(task.taskDefinitionKey())
                        .isEqualTo("Task_direct_manager"));
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("AgentRun did not reach terminal state within two minutes: "
                + started.processInstanceId());
    }
}

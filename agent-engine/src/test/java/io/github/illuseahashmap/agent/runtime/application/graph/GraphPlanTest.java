package io.github.illuseahashmap.agent.runtime.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.BooleanNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class GraphPlanTest {
    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    private final GraphTaskContract contract =
            new GraphTaskContract(
                    1,
                    "subject",
                    List.of(
                            new GraphTaskContract.FactRule("a", "lookup", "a", BooleanNode.TRUE),
                            new GraphTaskContract.FactRule("b", "lookup", "b", BooleanNode.TRUE)),
                    true);

    private GraphTaskContract.Report facts() {
        return contract.evaluate("{\"input\":{\"subject\":\"P-17\"}}", List.of(), codec);
    }

    private GraphPlan.Step step(String id, String fact, List<String> deps) {
        return new GraphPlan.Step(
                id, List.of(fact), List.of("lookup"), deps, List.of(fact), "REJECT");
    }

    private GraphPlan plan(List<GraphPlan.Step> steps) {
        return new GraphPlan(
                "p1", 1, 0, facts().contractDigest(), List.of("P-17"), "Verify", steps);
    }

    @Test
    void validDagAndDifferentOrderingAreAllowed() {
        plan(List.of(step("s2", "b", List.of("s1")), step("s1", "a", List.of())))
                .validate(facts(), Set.of("lookup"));
    }

    @Test
    void omittedFactsCannotWeakenChecklist() {
        assertThatThrownBy(
                        () ->
                                plan(List.of(step("s1", "a", List.of())))
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void foreignSubjectAndContractAreRejected() {
        var good = plan(List.of(step("s1", "a", List.of()), step("s2", "b", List.of())));
        assertThatThrownBy(
                        () ->
                                new GraphPlan(
                                                "p1",
                                                1,
                                                0,
                                                good.taskContractRef(),
                                                List.of("P-18"),
                                                "Verify",
                                                good.steps())
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                new GraphPlan(
                                                "p1",
                                                1,
                                                0,
                                                "other",
                                                good.subjectRefs(),
                                                "Verify",
                                                good.steps())
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void unauthorizedCapabilitiesAreRejected() {
        assertThatThrownBy(
                        () ->
                                plan(List.of(
                                                step("s1", "a", List.of()),
                                                step("s2", "b", List.of())))
                                        .validate(facts(), Set.of("debit")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void missingAndCircularDependenciesAreRejected() {
        assertThatThrownBy(
                        () ->
                                plan(List.of(
                                                step("s1", "a", List.of("missing")),
                                                step("s2", "b", List.of())))
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                plan(List.of(
                                                step("s1", "a", List.of("s2")),
                                                step("s2", "b", List.of("s1"))))
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void duplicateStepAndFactOwnershipAreRejected() {
        assertThatThrownBy(
                        () ->
                                plan(List.of(
                                                step("s1", "a", List.of()),
                                                step("s1", "b", List.of())))
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                plan(List.of(
                                                step("s1", "a", List.of()),
                                                step("s2", "a", List.of())))
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void weakenedRulesAndFailureDispositionAreRejected() {
        var weak =
                new GraphPlan.Step(
                        "s1", List.of("a"), List.of("lookup"), List.of(), List.of("b"), "CONTINUE");
        assertThatThrownBy(
                        () ->
                                plan(List.of(weak, step("s2", "b", List.of())))
                                        .validate(facts(), Set.of("lookup")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void dependenciesRequireActualFacts() {
        var p = plan(List.of(step("s1", "a", List.of()), step("s2", "b", List.of("s1"))));
        p.validate(facts(), Set.of("lookup"));
        assertThatThrownBy(() -> p.executable("s2", facts(), "lookup"))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(p.executable("s1", facts(), "lookup").stepId()).isEqualTo("s1");
    }

    @Test
    void modelCompletionAndExecutableFieldsAreNotInSchema() {
        var p = plan(List.of(step("s1", "a", List.of()), step("s2", "b", List.of())));
        String body = new String(codec.write(p), java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () ->
                                codec.read(
                                        body.replace("\"goal\":", "\"completed\":true,\"goal\":")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                        GraphPlan.class))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void transitiveDependenciesCannotBorrowAnEarlyFact() {
        var task =
                new GraphTaskContract(
                        1,
                        "subject",
                        List.of(
                                new GraphTaskContract.FactRule(
                                        "a", "lookup", "a", BooleanNode.TRUE),
                                new GraphTaskContract.FactRule(
                                        "b", "lookup", "b", BooleanNode.TRUE),
                                new GraphTaskContract.FactRule(
                                        "c", "lookup", "c", BooleanNode.TRUE)),
                        false);
        String output = "{\"subject\":\"P-17\",\"b\":true}";
        var early =
                task.evaluate(
                        "{\"input\":{\"subject\":\"P-17\"}}",
                        List.of(
                                new io.github.illuseahashmap.agent.runtime.application.port
                                        .GraphCognitionPort.ToolFact(
                                        "receipt:b",
                                        "lookup",
                                        "{\"subject\":\"P-17\"}",
                                        output,
                                        GraphGatewayCodec.sha256(output))),
                        codec);
        var p =
                new GraphPlan(
                        "p1",
                        1,
                        0,
                        early.contractDigest(),
                        List.of("P-17"),
                        "Verify",
                        List.of(
                                step("s1", "a", List.of()),
                                step("s2", "b", List.of("s1")),
                                step("s3", "c", List.of("s2"))));
        p.validate(early, Set.of("lookup"));
        assertThatThrownBy(() -> p.executable("s3", early, "lookup"))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void actionRevisionCannotOverflowAndSubjectCannotBeCoerced() throws Exception {
        var p = plan(List.of(step("s1", "a", List.of()), step("s2", "b", List.of())));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body =
                mapper.readTree(
                        "{\"plan_id\":\"p1\",\"plan_revision\":4294967297,\"plan_step_id\":\"s1\"}");
        assertThatThrownBy(() -> p.validateActionIdentity(body))
                .isInstanceOf(GraphGatewayException.class);
        var args = mapper.readTree("{\"subject\":17}");
        assertThatThrownBy(() -> GraphPlan.requireSubject(args, "subject", "17"))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void revisionCannotBeSilentlyReplaced() {
        assertThatThrownBy(
                        () ->
                                new GraphPlan(
                                        "p1",
                                        2,
                                        1,
                                        facts().contractDigest(),
                                        List.of("P-17"),
                                        "Verify",
                                        List.of(step("s1", "a", List.of()))))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void revisionRequiresEvidenceAndARealStrategyChange() {
        var original = plan(List.of(step("s1", "a", List.of()), step("s2", "b", List.of())));
        var revised =
                new GraphPlan(
                        "p1",
                        2,
                        1,
                        original.taskContractRef(),
                        original.subjectRefs(),
                        "Reorder evidence",
                        List.of(step("s1", "a", List.of("s2")), step("s2", "b", List.of())),
                        "Need b before a",
                        List.of("receipt:b"));
        revised.validate(facts(), Set.of("lookup"));
        revised.validateRevision(original, Set.of("receipt:b"));
        assertThatThrownBy(() -> revised.validateRevision(original, Set.of("receipt:other")))
                .isInstanceOf(GraphGatewayException.class);
        var renamed =
                new GraphPlan(
                        "p1",
                        2,
                        1,
                        original.taskContractRef(),
                        original.subjectRefs(),
                        "New wording",
                        List.of(step("renamed2", "b", List.of()), step("renamed1", "a", List.of())),
                        "Try again",
                        List.of("receipt:b"));
        assertThatThrownBy(() -> renamed.validateRevision(original, Set.of("receipt:b")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void oldPlanSerializationRetainsItsOriginalFields() {
        var old = plan(List.of(step("s1", "a", List.of()), step("s2", "b", List.of())));
        String body = new String(codec.write(old), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("revision_reason", "evidence_refs");
        assertThat(
                        codec.read(
                                body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                GraphPlan.class))
                .isEqualTo(old);
    }
}

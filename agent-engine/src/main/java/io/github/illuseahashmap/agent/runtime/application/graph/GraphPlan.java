package io.github.illuseahashmap.agent.runtime.application.graph;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded declarative advice. No executable expressions and no model-declared completion. */
public record GraphPlan(
        String planId,
        int revision,
        int parentRevision,
        String taskContractRef,
        List<String> subjectRefs,
        String goal,
        List<Step> steps,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                String revisionReason,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                List<String> evidenceRefs) {
    public GraphPlan(
            String id,
            int revision,
            int parent,
            String contract,
            List<String> subjects,
            String goal,
            List<Step> steps) {
        this(id, revision, parent, contract, subjects, goal, steps, null, null);
    }

    public record Policy(int version, String mode) {
        public Policy {
            require((version == 1 || version == 2) && "PLANNED".equals(mode));
        }
    }

    public record Context(Policy policy, GraphPlan accepted) {}

    public record Step(
            String stepId,
            List<String> factIds,
            List<String> capabilityChoices,
            List<String> dependsOn,
            List<String> successRuleRefs,
            String missingEvidenceAction) {}

    public GraphPlan {
        require(
                planId != null
                        && planId.matches("[A-Za-z0-9_:-]{1,128}")
                        && ((revision == 1 && parentRevision == 0)
                                || (revision == 2 && parentRevision == 1)));
        require(
                taskContractRef != null
                        && subjectRefs != null
                        && goal != null
                        && !goal.isBlank()
                        && goal.length() <= 512);
        require(steps != null && !steps.isEmpty() && steps.size() <= 32);
        subjectRefs = List.copyOf(subjectRefs);
        steps = List.copyOf(steps);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        require(evidenceRefs.size() <= 128);
        require(revision != 1 || (revisionReason == null && evidenceRefs.isEmpty()));
        require(
                revision == 1
                        || (revisionReason != null
                                && !revisionReason.isBlank()
                                && revisionReason.length() <= 512
                                && !evidenceRefs.isEmpty()));
    }

    public void validate(GraphTaskContract.Report facts, Set<String> allowed) {
        require(new GraphGatewayCodec().write(this).length <= 65536);
        require(
                facts != null
                        && taskContractRef.equals(facts.contractDigest())
                        && subjectRefs.equals(List.of(facts.subject())));
        var required = new HashMap<String, GraphTaskContract.FactState>();
        facts.facts().forEach(f -> required.put(f.id(), f));
        var byId = new HashMap<String, Step>();
        var covered = new HashSet<String>();
        for (var step : steps) {
            require(
                    step.stepId() != null
                            && step.stepId().matches("[A-Za-z0-9_:-]{1,128}")
                            && byId.put(step.stepId(), step) == null);
            unique(step.factIds(), false);
            unique(step.capabilityChoices(), false);
            unique(step.dependsOn(), true);
            unique(step.successRuleRefs(), false);
            require(
                    new HashSet<>(step.successRuleRefs()).equals(new HashSet<>(step.factIds()))
                            && "REJECT".equals(step.missingEvidenceAction()));
            var tools = new HashSet<String>();
            for (var fact : step.factIds()) {
                require(required.containsKey(fact) && covered.add(fact));
                tools.add(required.get(fact).tool());
            }
            require(
                    new HashSet<>(step.capabilityChoices()).equals(tools)
                            && allowed.containsAll(tools));
        }
        require(covered.equals(required.keySet()));
        var done = new HashSet<String>();
        for (var id : byId.keySet()) {
            visit(id, byId, new HashSet<>(), done);
        }
    }

    public void validateRevision(GraphPlan previous, Set<String> evidence) {
        require(
                previous != null
                        && revision == previous.revision() + 1
                        && parentRevision == previous.revision()
                        && planId.equals(previous.planId())
                        && taskContractRef.equals(previous.taskContractRef())
                        && subjectRefs.equals(previous.subjectRefs()));
        require(
                revisionReason != null
                        && !revisionReason.isBlank()
                        && revisionReason.length() <= 512
                        && !evidenceRefs.isEmpty()
                        && new HashSet<>(evidenceRefs).size() == evidenceRefs.size()
                        && evidence.containsAll(evidenceRefs));
        require(
                !strategy()
                        .equals(previous.strategy())); // IDs, description and array order alone are
        // not a new strategy.
    }

    private Set<String> strategy() {
        var byId = new HashMap<String, Step>();
        steps.forEach(step -> byId.put(step.stepId(), step));
        var codec = new GraphGatewayCodec();
        return steps.stream()
                .map(
                        step ->
                                codec.fingerprint(
                                        List.of(
                                                step.factIds().stream().sorted().toList(),
                                                step.capabilityChoices().stream().sorted().toList(),
                                                step.dependsOn().stream()
                                                        .flatMap(
                                                                id ->
                                                                        byId
                                                                                .get(id)
                                                                                .factIds()
                                                                                .stream())
                                                        .distinct()
                                                        .sorted()
                                                        .toList())))
                .collect(java.util.stream.Collectors.toSet());
    }

    public void validateActionIdentity(com.fasterxml.jackson.databind.JsonNode body) {
        require(
                body.path("plan_id").isTextual()
                        && body.path("plan_id").textValue().equals(planId)
                        && body.path("plan_revision").isIntegralNumber()
                        && body.path("plan_revision")
                                .bigIntegerValue()
                                .equals(java.math.BigInteger.valueOf(revision))
                        && body.path("plan_step_id").isTextual());
    }

    public static void requireSubject(
            com.fasterxml.jackson.databind.JsonNode args, String field, String subject) {
        require(args.path(field).isTextual() && args.path(field).textValue().equals(subject));
    }

    public Step executable(String id, GraphTaskContract.Report facts, String tool) {
        var step =
                steps.stream()
                        .filter(s -> s.stepId().equals(id))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new GraphGatewayException(
                                                GraphGatewayException.Code.PLAN_DENIED));
        require(step.capabilityChoices().contains(tool));
        var satisfied =
                facts.facts().stream()
                        .filter(f -> "SATISFIED".equals(f.status()))
                        .map(GraphTaskContract.FactState::id)
                        .collect(java.util.stream.Collectors.toSet());
        var checked = new HashSet<String>();
        for (var dependency : step.dependsOn()) {
            requireEvidence(dependency, satisfied, checked);
        }
        return step;
    }

    private void requireEvidence(String id, Set<String> satisfied, Set<String> checked) {
        if (!checked.add(id)) {
            return;
        }
        var prior = steps.stream().filter(s -> s.stepId().equals(id)).findFirst().orElseThrow();
        require(satisfied.containsAll(prior.factIds()));
        for (var dependency : prior.dependsOn()) {
            requireEvidence(dependency, satisfied, checked);
        }
    }

    private static void unique(List<String> values, boolean emptyAllowed) {
        require(
                values != null
                        && values.size() <= 32
                        && (emptyAllowed || !values.isEmpty())
                        && values.stream().allMatch(Objects::nonNull)
                        && new HashSet<>(values).size() == values.size());
    }

    private static void visit(
            String id, Map<String, Step> steps, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) {
            return;
        }
        require(steps.containsKey(id) && visiting.add(id));
        for (var dependency : steps.get(id).dependsOn()) {
            visit(dependency, steps, visiting, done);
        }
        visiting.remove(id);
        done.add(id);
    }
}

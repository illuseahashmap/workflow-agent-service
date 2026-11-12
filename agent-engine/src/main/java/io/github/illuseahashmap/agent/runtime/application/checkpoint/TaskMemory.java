package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.definition.domain.DecisionBinding;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest.ToolResult;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor.Command;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Per-run working memory. The summary is non-authoritative; receipts and decision facts are not summarized. */
public final class TaskMemory {
    /** Execution-framework-neutral identity for authoritative facts and working memory. */
    public record Context(String tenantCode, long runId, String processInstanceId,
                          io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion version, String input) { }
    public record Receipt(String reference, String callId, String tool, String argumentsJson, String outputDigest) {}
    public record PendingTool(String name, String callId, String argumentsJson) {}
    public record Candidate(long providerId, String requestedModel, ModelProviderResponse response) {}
    public record State(String owner, String route, Set<String> routeTools, List<Receipt> receipts, String notes, int revision, List<Fold> folds,
                        PendingTool pendingTool, Candidate candidate) {}
    public record Fold(int throughStep, String strategy, String model, String inputDigest, String notesDigest) {}
    private final ObjectMapper json;
    private final CheckpointContextPolicy policy;
    private final List<Receipt> receipts = new ArrayList<>();
    private final Set<String> factRefs = new LinkedHashSet<>();
    private final Map<String,String> originals = new HashMap<>();
    private final DecisionBinding binding;
    private final io.github.illuseahashmap.agent.definition.domain.CitationClaim claim;
    private final Set<String> observedCitations = new LinkedHashSet<>();
    private final String input, owner;
    private Set<String> routeTools;
    private String route, notes = "", expected, fact;
    private final List<Fold> folds = new ArrayList<>();

    public TaskMemory(Command c, ObjectMapper json, CheckpointContextPolicy policy) {
        this(new Context(c.tenantCode(), c.runId(), c.processInstanceId(), c.version(), c.input()), json, policy);
    }

    public TaskMemory(Context c, ObjectMapper json, CheckpointContextPolicy policy) {
        this.json = json;
        this.policy = policy;
        this.input = c.input();
        this.owner = policy.digest(encode(Arrays.asList(c.tenantCode(), c.runId(), c.processInstanceId(),
                c.version().id(), policy.digest(input))));
        this.binding = DecisionBinding.parse(json, c.version().outputSchema());
        this.claim = io.github.illuseahashmap.agent.definition.domain.CitationClaim.parse(json, c.version().outputSchema());
    }

    public void route(String route, Set<String> tools) { this.route = route; this.routeTools = Set.copyOf(tools); }
    public ToolResult observe(Receipt r, String original) {
        if (r == null || original == null || !policy.digest(original).equals(r.outputDigest())) throw invalid("Tool result digest mismatch");
        if (claim != null && "knowledge_search".equals(r.tool())) {
            try {
                var result=json.readTree(original);
                if (!"SUCCESS".equals(result.path("status").textValue()) || !result.path("citations").isArray()
                        || !result.path("evidence").isArray()) throw unverified();
                var evidences=new java.util.HashSet<JsonNode>();
                for (var e:result.get("evidence")) evidences.add(e.path("reference"));
                for (var citation:result.get("citations")) {
                    var ref=citation.path("reference");
                    if (!evidences.remove(ref) || !"CHUNK".equals(ref.path("sourceType").textValue())
                            || !ref.path("locator").isTextual()) throw unverified();
                    observedCitations.add(ref.get("locator").textValue());
                }
                if (!evidences.isEmpty()) throw unverified();
            } catch(java.io.IOException e){throw unverified();}
        }
        if (binding != null && binding.tool().equals(r.tool())) {
            try {
                var subject = json.readTree(input).path(binding.subjectField());
                var source = json.readTree(original);
                var raw = source.path(binding.factField());
                if (!subject.isTextual() || subject.asText().isBlank() || !subject.equals(source.path(binding.subjectField()))
                        || !raw.isTextual() || !binding.cases().containsKey(raw.textValue())
                        || (fact != null && !fact.equals(raw.textValue()))) throw unverified();
                fact = raw.textValue();
                expected = binding.cases().get(fact);
                factRefs.add(r.reference());
            } catch (java.io.IOException | NullPointerException e) { throw unverified(); }
        }
        if (originals.putIfAbsent(r.reference(), original) != null) throw invalid("Duplicate result reference");
        receipts.add(r);
        return new ToolResult(r.callId(), r.tool(), r.argumentsJson(), policy.preview(original));
    }

    /** Merge only the previous bounded notes and this new delta; never send the full historical transcript. */
    public void merge(Receipt r, String original, String model, Function<String,String> summarize) {
        String delta = r.reference() + ": " + policy.preview(original);
        String priorView = policy.preview(notes);
        String merged = priorView.isEmpty() ? delta : priorView + "\n" + delta;
        if (original.length() > policy.inlineLimit() || merged.length() > policy.inlineLimit()) {
            String mergeInput = "Previous notes:\n" + priorView + "\nNew observation:\n" + delta;
            String candidate;
            try {
                candidate = summarize.apply(mergeInput);
            } catch (ModelProviderException unavailable) {
                candidate = null; // An optional summary must not prevent recovery. Auth and deadline failures still propagate.
            }
            notes = policy.preview(candidate == null || candidate.isBlank() ? delta + "\n" + notes : candidate);
            boolean fallback = candidate == null || candidate.isBlank();
            folds.add(new Fold(receipts.size(), fallback ? "extractive:v1" : "model-merge:v1",
                    fallback ? null : model, policy.digest(mergeInput), policy.digest(notes)));
        } else notes = merged;
    }

    public String readSegment(String response) {
        JsonNode read;
        try { read = response == null ? null : json.readTree(response); }
        catch (java.io.IOException ignored) { return null; }
        if (read == null || !"READ_RESULT".equals(read.path("action").asText())) return null;
        String ref = read.path("reference").asText("");
        String pointer = read.path("pointer").asText("");
        String body = originals.get(ref);
        if (body == null || pointer.length() > 256 || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
            throw invalid("Unknown result reference or invalid JSON pointer for this run");
        }
        if (!pointer.isEmpty()) {
            try {
                var root = json.readTree(body);
                if (root == null) throw invalid("Empty result has no JSON fields");
                var part = root.at(pointer);
                if (part.isMissingNode()) throw invalid("Referenced field does not exist");
                body = part.isTextual() ? part.textValue() : part.toString();
            } catch (java.io.IOException | IllegalArgumentException e) { throw invalid("Invalid result path"); }
        }
        // Offset counts Unicode code points; length is bounded in UTF-16 to match the request budget.
        var offset = read.path("offset");
        if (!offset.isMissingNode() && (!offset.isIntegralNumber() || !offset.canConvertToInt())) throw invalid("Invalid result offset");
        int start = offset.asInt(0);
        if (start < 0 || start > body.codePointCount(0, body.length())) throw invalid("Result offset outside source");
        int from = body.offsetByCodePoints(0, start);
        int end = Math.min(body.length(), from + policy.inlineLimit());
        if (end < body.length() && Character.isLowSurrogate(body.charAt(end))) end--;
        return encode(Map.of("reference",ref,"pointer",pointer,"offset",start,
                "nextOffset",start + body.codePointCount(from,end),"more",end < body.length(),"value",body.substring(from,end)));
    }

    public String prompt() {
        return "结构化执行计划：\n" + route + "\n用户请求：\n" + input + "\n非权威工作摘要：\n" + policy.preview(notes)
                + "\n工具来源：" + encode(receipts.stream().map(Receipt::reference).toList())
                + (fact == null ? "" : "\n经工具核对的决策字段：" + encode(Map.of(
                    "field", binding.factField(), "value", fact, "expectedOutput", expected, "sources", factRefs)));
    }
    public void verify(String output) {
        if (claim != null) {
            try {
                var amountInput=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(input);
                if (!claim.supported(amountInput, json.readTree(output), observedCitations)) throw unverified();
            } catch (java.io.IOException | NullPointerException e) { throw unverified(); }
        }
        if (binding == null) return;
        try {
            var answer = json.readTree(output);
            var value = answer.path(binding.outputField());
            if (binding.referenceField() != null && !factRefs.contains(answer.path(binding.referenceField()).asText(""))) throw unverified();
            if (expected == null || !value.isTextual() || !expected.equals(value.textValue())) throw unverified();
        } catch (java.io.IOException | NullPointerException e) { throw unverified(); }
    }
    public String snapshot() {
        return snapshot(null, null);
    }
    public String snapshot(PendingTool pendingTool, Candidate candidate) {
        return encode(new State(owner, route, routeTools, List.copyOf(receipts), notes, folds.size(),
                List.copyOf(folds), pendingTool, candidate));
    }
    public State open(String snapshot, int cursor) {
        try {
            State state = json.readValue(snapshot, State.class);
            if (state == null || !owner.equals(state.owner()) || state.route() == null || state.routeTools() == null || state.receipts() == null
                    || state.receipts().stream().anyMatch(r -> r == null || r.tool() == null || r.argumentsJson() == null
                            || r.reference() == null || r.callId() == null)
                    || state.receipts().size() != cursor - 1 || state.notes() == null
                    || state.notes().length() > CheckpointContextPolicy.STORED_NOTES_LIMIT || state.revision() < 0
                    || state.folds() == null || state.revision() != state.folds().size() || state.revision() > state.receipts().size()) throw invalid("Memory ownership or cursor mismatch");
            int previous = 0;
            for (Fold fold : state.folds()) {
                if (fold == null || fold.throughStep() <= previous || fold.throughStep() >= cursor
                        || fold.inputDigest() == null || fold.notesDigest() == null
                        || !("model-merge:v1".equals(fold.strategy()) || "extractive:v1".equals(fold.strategy()))) {
                    throw invalid("Invalid compaction lineage");
                }
                previous = fold.throughStep();
            }
            folds.addAll(state.folds());
            route(state.route(), state.routeTools());
            notes = state.notes();
            return state;
        } catch (java.io.IOException e) { throw invalid("Invalid memory snapshot"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException e) { throw invalid("Cannot encode task memory"); }
    }
    private static AgentExecutionException invalid(String message) {
        return new AgentExecutionException(new AgentFailure("AGENT_CHECKPOINT_INVALID",
                AgentFailureCategory.EXECUTION_UNEXPECTED, false, ResultStatus.FAILED, message));
    }
    private static AgentExecutionException unverified() {
        return new AgentExecutionException(new AgentFailure("AGENT_DECISION_UNVERIFIED",
                AgentFailureCategory.RESULT_POLICY, false, ResultStatus.REJECTED,
                "Decision does not match the authoritative record for the current subject"));
    }
}

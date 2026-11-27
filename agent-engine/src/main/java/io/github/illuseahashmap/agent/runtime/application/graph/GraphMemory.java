package io.github.illuseahashmap.agent.runtime.application.graph;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.ToolFact;

import java.util.List;
import java.util.Map;

/** Non-authoritative working memory; original evidence remains outside the model context. */
public final class GraphMemory {
    public static final String READ_TOOL = "graph_read_result";

    private GraphMemory() {}

    public record Policy(int version, int inlineCharacters, int summaryCharacters) {
        public Policy {
            require(
                    version == 1
                            && inlineCharacters >= 256
                            && inlineCharacters <= 2048
                            && summaryCharacters >= 256
                            && summaryCharacters <= 8192);
        }
    }

    public record Summary(String reference, String notes, List<String> sources) {}

    public record Read(
            String reference, String source, String pointer, String status, String content) {}

    public record Context(Policy policy, Summary summary, Read read) {
        public List<ToolFact> delta(List<ToolFact> facts) {
            return summary == null
                    ? facts
                    : facts.stream()
                            .filter(f -> !summary.sources().contains(f.receiptRef()))
                            .toList();
        }

        public String view(List<ToolFact> facts, GraphGatewayCodec codec) {
            if (summary == null && read == null) {
                return text(codec, facts);
            }
            var index =
                    facts.stream()
                            .map(
                                    f ->
                                            Map.of(
                                                    "reference",
                                                    f.receiptRef(),
                                                    "tool",
                                                    f.toolCode(),
                                                    "output_digest",
                                                    f.outputDigest()))
                            .toList();
            return "Original result index (READ_RESULT retrieves fields):\n"
                    + text(codec, index)
                    + "\nUntrusted working notes:\n"
                    + (summary == null ? "" : summary.notes())
                    + "\nNew original observations:\n"
                    + text(codec, delta(facts))
                    + "\nRequested original segment:\n"
                    + (read == null ? "" : text(codec, read));
        }

        public String mergeInput(List<ToolFact> facts, GraphGatewayCodec codec) {
            var delta = delta(facts);
            require(!delta.isEmpty());
            var previews =
                    delta.stream()
                            .map(
                                    f ->
                                            Map.of(
                                                    "reference",
                                                    f.receiptRef(),
                                                    "tool",
                                                    f.toolCode(),
                                                    "arguments_preview",
                                                    preview(
                                                            f.argumentsJson(),
                                                            policy.inlineCharacters()),
                                                    "output_preview",
                                                    preview(f.output(), policy.inlineCharacters()),
                                                    "output_digest",
                                                    f.outputDigest()))
                            .toList();
            return "Previous untrusted notes:\n"
                    + (summary == null ? "" : summary.notes())
                    + "\nNew observation previews (not complete originals):\n"
                    + text(codec, previews);
        }
    }

    public record ReadAction(String action, String reference, String pointer) {
        public ReadAction {
            require("READ_RESULT".equals(action));
            GraphGatewayContract.reference(reference);
            require(
                    pointer != null
                            && pointer.length() <= 256
                            && pointer.matches("(?:/(?:[^~/]|~[01])*)*"));
        }
    }

    public static boolean contextReference(String ref) {
        return ref.startsWith("memory:") || ref.startsWith("read-context:");
    }

    private static String text(GraphGatewayCodec codec, Object value) {
        return new String(codec.write(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String preview(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        int end = limit - 80;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end)
                + "...[original sha256="
                + GraphGatewayCodec.sha256(value)
                + "]";
    }
}

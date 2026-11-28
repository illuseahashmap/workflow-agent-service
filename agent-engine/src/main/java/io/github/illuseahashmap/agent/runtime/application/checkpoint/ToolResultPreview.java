package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Model-facing projection only. Never replaces the original result or its digest. */
public final class ToolResultPreview {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResultPreview() {}

    public static String render(String receipt, String original, int limit) {
        if (original.length() <= limit) {
            return original;
        }
        ObjectNode view = JSON.createObjectNode();
        view.put("complete", false);
        ObjectNode read = view.putObject("readNext");
        read.put("action", "READ_RESULT");
        read.put("reference", receipt);
        read.put("pointer", "");
        try {
            var root = JSON.readTree(original);
            if (root != null && root.path("evidence").isArray()) {
                for (String field : new String[] {"status", "abstained"}) {
                    if (root.has(field) && root.get(field).isValueNode()) {
                        view.set(field, root.get(field));
                    }
                }
                var evidence = root.path("evidence");
                var shown = view.putArray("evidence");
                view.put("omittedEvidence", evidence.size());
                for (int i = 0; i < evidence.size(); i++) {
                    // A complete evidence entry retains its text/reference association.
                    read.put("pointer", "/evidence/" + (i + 1));
                    shown.add(evidence.get(i));
                    view.put("omittedEvidence", evidence.size() - i - 1);
                    if (view.toString().length() > limit) {
                        shown.remove(shown.size() - 1);
                        read.put("pointer", "/evidence/" + i);
                        view.put("omittedEvidence", evidence.size() - i);
                        break;
                    }
                }
                if (shown.size() == evidence.size()) {
                    // The evidence is complete, but other result fields remain external.
                    read.put("pointer", "");
                }
                return view.toString();
            }
        } catch (java.io.IOException ignored) {
            // Text tools retain a bounded excerpt inside a valid JSON envelope.
        }
        view.put("excerpt", "");
        for (int end = 0; end < original.length(); ) {
            int next = end + Character.charCount(original.codePointAt(end));
            view.put("excerpt", original.substring(0, next));
            if (view.toString().length() > limit) {
                view.put("excerpt", original.substring(0, end));
                break;
            }
            end = next;
        }
        return view.toString();
    }
}

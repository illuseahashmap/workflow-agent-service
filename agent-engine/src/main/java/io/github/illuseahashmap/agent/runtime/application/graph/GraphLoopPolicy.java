package io.github.illuseahashmap.agent.runtime.application.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Trusted, frozen per Run. No field is ignored by default, even if its name resembles metadata. */
public record GraphLoopPolicy(Map<String, List<String>> ignoredArgumentFields,
                              Map<String, List<String>> ignoredResultFields) {
    private static final Set<String> METADATA = Set.of("traceId", "trace_id", "requestId", "request_id");
    public GraphLoopPolicy {
        ignoredArgumentFields = checked(ignoredArgumentFields);
        ignoredResultFields = checked(ignoredResultFields);
    }
    public static GraphLoopPolicy defaults() { return new GraphLoopPolicy(Map.of(), Map.of()); }
    private static Map<String, List<String>> checked(Map<String, List<String>> fields) {
        if (fields == null || fields.size() > 200) { throw new IllegalArgumentException("Invalid loop policy"); }
        var result = new TreeMap<String, List<String>>();
        fields.forEach((tool, names) -> {
            if (tool == null || !tool.matches("[A-Za-z0-9_.:-]{1,128}") || names == null
                    || names.size() > 4 || !METADATA.containsAll(names)) { throw new IllegalArgumentException("Invalid loop metadata declaration"); }
            result.put(tool, names.stream().distinct().sorted().toList());
        });
        return java.util.Collections.unmodifiableMap(result);
    }
}

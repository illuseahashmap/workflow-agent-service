package io.github.illuseahashmap.workflow.knowledge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Index-time vocabulary: an alias locates a header; it is never a citation or query rewrite. */
final class NativeScopeVocabulary {
    private static final Map<String,List<String>> ALIASES = load();

    static List<String> headerKeys(String caption) {
        var keys = new LinkedHashSet<>(NativeTableScope.keys(caption));
        keys.addAll(ALIASES.getOrDefault(caption,List.of()));
        return List.copyOf(keys);
    }

    private static Map<String,List<String>> load() {
        var entries = new HashMap<String,List<String>>();
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                NativeScopeVocabulary.class.getResourceAsStream("/knowledge/scope-aliases-v1.tsv")), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                var terms = List.of(line.split("\t",-1));
                if (terms.size()<2 || terms.getFirst().isBlank() || terms.stream().skip(1)
                        .anyMatch(term -> !term.matches("[\\p{IsHan}]{2,8}"))
                        || entries.putIfAbsent(terms.getFirst(),List.copyOf(terms.subList(1,terms.size())))!=null)
                    throw new IllegalStateException("Invalid native scope vocabulary");
            }
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        return Map.copyOf(entries);
    }
    private NativeScopeVocabulary() {}
}

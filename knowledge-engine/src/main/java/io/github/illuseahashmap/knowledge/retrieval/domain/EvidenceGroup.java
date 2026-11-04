package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;

/** Internal selection unit, not a new source type. Its score applies to the whole rerank text. */
public record EvidenceGroup(String groupId, List<EvidenceReference> sources, String rerankText,
                            EvidenceScore selectionScore) {
    public EvidenceGroup {
        if (groupId == null || groupId.isBlank() || sources == null || sources.isEmpty()
                || rerankText == null || rerankText.isBlank()) {
            throw new IllegalArgumentException("An evidence group requires identity, complete sources and text");
        }
        sources = List.copyOf(sources);
    }
}

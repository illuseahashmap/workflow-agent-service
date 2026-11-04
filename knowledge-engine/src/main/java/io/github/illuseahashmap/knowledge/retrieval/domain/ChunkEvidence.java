package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Objects;

/** First-slice evidence produced by keyword/vector/hybrid retrievers. */
public record ChunkEvidence(
        EvidenceReference reference,
        String text,
        double score,
        String title,
        EvidenceScore.Kind scoreKind,
        String scoreVersion
) implements Evidence {
    public ChunkEvidence(EvidenceReference reference, String text, double score, String title) {
        this(reference, text, score, title, EvidenceScore.Kind.UNSPECIFIED, "unclassified");
    }

    public static ChunkEvidence scored(EvidenceReference reference, String text, double score, String title, String strategy) {
        var scale = ScorePolicy.forStrategy(strategy, 0);
        return new ChunkEvidence(reference, text, score, title, scale.kind(), scale.version());
    }

    public EvidenceScore scoring() { return new EvidenceScore(scoreKind, scoreVersion, score); }
    public ChunkEvidence {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        text = Objects.requireNonNull(text, "text must not be null");
        title = Objects.requireNonNullElse(title, "");
        new EvidenceScore(scoreKind, scoreVersion, score);
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.CHUNK;
    }
}

package io.github.illuseahashmap.knowledge.retrieval.domain;

/** Frozen threshold for exactly one score scale; never a probability-of-correctness gate. */
public record ScorePolicy(EvidenceScore.Kind kind, String version, double minimum) {
    public ScorePolicy {
        if (kind == null || kind == EvidenceScore.Kind.UNSPECIFIED || version == null || version.isBlank()
                || !Double.isFinite(minimum) || (kind != EvidenceScore.Kind.RERANK && minimum < 0)
                || ((kind == EvidenceScore.Kind.COSINE_SIMILARITY || kind == EvidenceScore.Kind.RRF_NORMALIZED) && minimum > 1)) {
            throw new IllegalArgumentException("A calibrated score policy is required");
        }
    }
    public boolean accepts(EvidenceScore score) {
        return score != null && score.kind() == kind && version.equals(score.version()) && score.value() >= minimum;
    }
    public static ScorePolicy forStrategy(String strategy, double minimum) {
        return switch (strategy) {
            case "KEYWORD", "AUTO" -> new ScorePolicy(EvidenceScore.Kind.KEYWORD_RANK, "postgres-simple-lexical-v1", minimum);
            case "VECTOR" -> new ScorePolicy(EvidenceScore.Kind.COSINE_SIMILARITY, "cosine-clamped-v1", minimum);
            case "HYBRID" -> new ScorePolicy(EvidenceScore.Kind.RRF_NORMALIZED, "rrf-k60-two-channel-v1", minimum);
            default -> throw new IllegalArgumentException("No score policy is defined for strategy: " + strategy);
        };
    }
}

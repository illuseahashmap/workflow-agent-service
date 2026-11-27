package io.github.illuseahashmap.knowledge.retrieval.domain;

/** A score is meaningful only together with its algorithm/model calibration version. */
public record EvidenceScore(Kind kind, String version, double value) {
    public enum Kind { UNSPECIFIED, KEYWORD_RANK, COSINE_SIMILARITY, RRF_NORMALIZED, RERANK }
    public EvidenceScore {
        if (kind == null || version == null || version.isBlank() || !Double.isFinite(value)
                || (kind != Kind.RERANK && value < 0)
                || ((kind == Kind.COSINE_SIMILARITY || kind == Kind.RRF_NORMALIZED) && value > 1)) {
            throw new IllegalArgumentException("Invalid typed evidence score");
        }
    }
}

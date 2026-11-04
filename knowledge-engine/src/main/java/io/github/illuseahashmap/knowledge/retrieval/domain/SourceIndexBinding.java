package io.github.illuseahashmap.knowledge.retrieval.domain;

/** One immutable source-to-index member of a published retrieval snapshot. */
public record SourceIndexBinding(String sourceCode, long indexVersionId) {
    public SourceIndexBinding {
        if (sourceCode == null || sourceCode.isBlank() || indexVersionId < 1) {
            throw new IllegalArgumentException("A source and positive frozen index are required");
        }
    }
}

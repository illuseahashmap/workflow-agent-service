package io.github.illuseahashmap.knowledge.ingestion.domain;

/** A newer immutable document version already belongs to the source snapshot. */
public final class DocumentSupersededException extends RuntimeException {
    public DocumentSupersededException() {
        super("Document version has been superseded");
    }
}

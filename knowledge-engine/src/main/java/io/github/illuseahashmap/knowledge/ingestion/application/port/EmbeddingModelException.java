package io.github.illuseahashmap.knowledge.ingestion.application.port;

/** Stable, credential-free classification for embedding provider failures. */
public final class EmbeddingModelException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    public EmbeddingModelException(String errorCode, boolean retryable) {
        super(errorCode);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public EmbeddingModelException(String errorCode, boolean retryable, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}

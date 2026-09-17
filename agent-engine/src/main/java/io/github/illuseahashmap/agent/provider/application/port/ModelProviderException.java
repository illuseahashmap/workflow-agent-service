package io.github.illuseahashmap.agent.provider.application.port;

public final class ModelProviderException extends RuntimeException {

    private final String errorCode;
    private final ModelProviderFailureKind failureKind;
    private final String safeDetail;

    public ModelProviderException(
            String errorCode,
            ModelProviderFailureKind failureKind,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
        this.failureKind = failureKind;
        this.safeDetail = null;
    }

    public ModelProviderException(
            String errorCode,
            ModelProviderFailureKind failureKind,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.failureKind = failureKind;
        this.safeDetail = null;
    }

    public ModelProviderException(
            String errorCode,
            ModelProviderFailureKind failureKind,
            String message,
            String safeDetail
    ) {
        super(message);
        this.errorCode = errorCode;
        this.failureKind = failureKind;
        this.safeDetail = safeDetail;
    }

    public String errorCode() {
        return errorCode;
    }

    public ModelProviderFailureKind failureKind() {
        return failureKind;
    }

    public String safeDetail() {
        return safeDetail;
    }
}

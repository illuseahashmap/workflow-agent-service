package io.github.illuseahashmap.agent.provider.application.port;

public final class ModelProviderException extends RuntimeException {

    private final String errorCode;
    private final ModelProviderFailureKind failureKind;

    public ModelProviderException(
            String errorCode,
            ModelProviderFailureKind failureKind,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
        this.failureKind = failureKind;
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
    }

    public String errorCode() {
        return errorCode;
    }

    public ModelProviderFailureKind failureKind() {
        return failureKind;
    }
}

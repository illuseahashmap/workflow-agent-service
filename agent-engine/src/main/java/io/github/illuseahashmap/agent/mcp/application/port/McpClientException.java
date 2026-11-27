package io.github.illuseahashmap.agent.mcp.application.port;

/** Typed boundary failure from an MCP connector; never expose remote detail as a runtime status. */
public final class McpClientException extends RuntimeException {

    private final String errorCode;
    private final McpFailureKind failureKind;
    private final boolean retryable;

    public McpClientException(String errorCode, McpFailureKind failureKind,
                              boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.failureKind = failureKind;
        this.retryable = retryable;
    }

    public McpClientException(String errorCode, McpFailureKind failureKind,
                              boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.failureKind = failureKind;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public McpFailureKind failureKind() {
        return failureKind;
    }

    public boolean retryable() {
        return retryable;
    }
}

package io.github.illuseahashmap.workflow.shared.exception;

public enum ErrorCode {

    BAD_REQUEST("BAD_REQUEST", "Invalid request"),
    UNAUTHORIZED("UNAUTHORIZED", "Unauthorized request"),
    FORBIDDEN("FORBIDDEN", "Forbidden request"),
    NOT_FOUND("NOT_FOUND", "Resource not found"),
    CONFLICT("CONFLICT", "Resource conflict"),
    RATE_LIMITED("RATE_LIMITED", "Too many requests"),
    WORKFLOW_ERROR("WORKFLOW_ERROR", "Workflow operation failed"),
    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

package io.github.illuseahashmap.agent.runtime.application.graph;

/** Stable public reasons only; never serialize a cause, token, argument, or tool body. */
public final class GraphGatewayException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST(400), AUTHORITY_DENIED(403), BINDING_MISMATCH(403),
        REFERENCE_DENIED(403), DEADLINE_EXPIRED(409), OPERATION_UNSUPPORTED(422),
        TOOL_REJECTED(422), TOOL_IN_PROGRESS(409), TOOL_OUTCOME_UNKNOWN(409), TOOL_TRANSIENT(503),
        MODEL_IN_PROGRESS(409), MODEL_BUDGET_EXHAUSTED(409), MODEL_TRANSIENT(503), MODEL_REJECTED(422), INTERNAL_FAILURE(503);

        private final int status;
        Code(int status) { this.status = status; }
        public int status() { return status; }
    }

    private final Code code;
    public GraphGatewayException(Code code) {
        super(code.name());
        this.code = code;
    }
    public Code code() { return code; }
}

package io.github.illuseahashmap.workflow.shared.response;

import io.github.illuseahashmap.workflow.shared.context.CurrentTraceContext;

public record ApiResponse<T>(String code, String message, T data, String traceId) {

    private static final String SUCCESS_CODE = "SUCCESS";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "success", data, CurrentTraceContext.currentOrNull());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(SUCCESS_CODE, "success", null, CurrentTraceContext.currentOrNull());
    }

    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(code, message, null, CurrentTraceContext.currentOrNull());
    }
}

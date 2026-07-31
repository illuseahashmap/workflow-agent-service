package io.github.illuseahashmap.workflow.common.response;

public record ApiResponse<T>(String code, String message, T data) {

    private static final String SUCCESS_CODE = "SUCCESS";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "success", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(SUCCESS_CODE, "success", null);
    }

    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}

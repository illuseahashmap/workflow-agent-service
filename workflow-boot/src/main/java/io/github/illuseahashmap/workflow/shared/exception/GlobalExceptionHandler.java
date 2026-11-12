package io.github.illuseahashmap.workflow.shared.exception;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.flowable.common.engine.api.FlowableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        if (capacityExhausted(exception)) { return capacityResponse(); }
        HttpStatus status = switch (exception.getErrorCode()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String message = status.is5xxServerError()
                ? exception.getErrorCode().defaultMessage()
                : exception.getMessage();
        if (status.is5xxServerError()) {
            LOGGER.error("Business operation failed with internal error", exception);
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(exception.getErrorCode().code(), message));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException validationException
                && validationException.getBindingResult().getFieldError() != null
                ? validationException.getBindingResult().getFieldError().getField() + " "
                    + validationException.getBindingResult().getFieldError().getDefaultMessage()
                : ErrorCode.BAD_REQUEST.defaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST.code(), message));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(
            org.springframework.http.converter.HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST.code(), "Malformed request body"));
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequestParameter(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST.code(), "Invalid request parameter"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(ApiResponse.fail(ErrorCode.BAD_REQUEST.code(), "Upload exceeds size limit"));
    }

    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> invalidMultipart(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.BAD_REQUEST.code(), "Invalid multipart request"));
    }

    @ExceptionHandler(FlowableException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowableException(FlowableException exception) {
        if (capacityExhausted(exception)) { return capacityResponse(); }
        LOGGER.warn("Flowable operation failed", exception);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ErrorCode.WORKFLOW_ERROR.code(), ErrorCode.WORKFLOW_ERROR.defaultMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ErrorCode.FORBIDDEN.code(), "Insufficient permission"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        if (capacityExhausted(exception)) { return capacityResponse(); }
        LOGGER.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }
    private boolean capacityExhausted(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof java.sql.SQLException sql && "PWA01".equals(sql.getSQLState())) { return true; }
        }
        return false;
    }
    private ResponseEntity<ApiResponse<Void>> capacityResponse() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.fail(ErrorCode.RATE_LIMITED.code(), "Agent capacity exhausted; retry after existing runs complete"));
    }
}

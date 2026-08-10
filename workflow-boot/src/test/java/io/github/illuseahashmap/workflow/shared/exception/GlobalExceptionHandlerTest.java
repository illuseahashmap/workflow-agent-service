package io.github.illuseahashmap.workflow.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.stream.Stream;
import org.flowable.common.engine.api.FlowableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @ParameterizedTest
    @MethodSource("businessErrors")
    void mapsBusinessErrorsToStableHttpResponses(ErrorCode errorCode, HttpStatus expectedStatus) {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleBusinessException(
                new BusinessException(errorCode, "safe message"));

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(errorCode.code());
        assertThat(response.getBody().message()).isEqualTo(
                expectedStatus.is5xxServerError() ? errorCode.defaultMessage() : "safe message");
    }

    @Test
    void reportsTheRejectedFieldForMethodArgumentValidation() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "name", "must not be blank"));
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleValidationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("name must not be blank");
    }

    @Test
    void usesGenericMessageForOtherValidationFailures() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleValidationException(
                new IllegalArgumentException("internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.BAD_REQUEST.defaultMessage());
    }

    @Test
    void mapsFlowableFailuresWithoutLeakingEngineDetails() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleFlowableException(
                new FlowableException("engine detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.WORKFLOW_ERROR.defaultMessage());
    }

    @Test
    void mapsAuthorizationFailures() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleAccessDeniedException(
                new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.FORBIDDEN.code());
    }

    @Test
    void hidesUnhandledExceptionDetails() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleException(
                new IllegalStateException("database detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
                Arguments.of(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
                Arguments.of(ErrorCode.CONFLICT, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST),
                Arguments.of(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS),
                Arguments.of(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR));
    }
}

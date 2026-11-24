package com.application.e_wallet.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.InvalidFormatException;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    //============  Your existing duplicate resource handler ============

    @ExceptionHandler(DuplicationResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(DuplicationResourceException exception, HttpServletRequest request) {

        HttpStatus status = HttpStatus.CONFLICT;

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(exception.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity
                .status(status)
                .body(errorResponse);
    }

    //============ Bad Request Handler =============

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            BadRequestException exception,
            HttpServletRequest request
    ) {

        HttpStatus status = HttpStatus.BAD_REQUEST;

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(exception.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity
                .status(status)
                .body(errorResponse);
    }

    //============ Validation Field Error Handler (@valid) =============

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
            HttpStatus status = HttpStatus.BAD_REQUEST;
            Map<String, String> fieldErrors = new HashMap<>();

            exception.getBindingResult().getAllErrors().forEach((error) -> {
                String fieldName = (error instanceof FieldError fieldError)
                        ? fieldError.getField()
                        : error.getObjectName();
                String errorMessage = error.getDefaultMessage();
                fieldErrors.put(fieldName, errorMessage);
            });

            ErrorResponse errorResponse = ErrorResponse.builder()
                    .timestamp(Instant.now())
                    .status(status.value())
                    .error(status.getReasonPhrase())
                    .message("Validation failed for the request payload")
                    .path(request.getRequestURI())
                    .errors(fieldErrors)
                    .build();

            return ResponseEntity.status(status).body(errorResponse);
    }

    //============ Malformed JSON / Type Mismatch / Invalid Enum Handler ============
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request

    ){

        HttpStatus status = HttpStatus.BAD_REQUEST;
        String message = "Malformed JSON request. Please verify the request body format.";
        Map<String, String> fieldErrors = new HashMap<>();

        if (exception.getCause() instanceof InvalidFormatException ife) {
            String fieldName = ife.getPath().isEmpty()
                    ? "unknown"
                    : ife.getPath().get(ife.getPath().size() - 1).getPropertyName();

            message = "Invalid value provided in the request payload";
            fieldErrors.put(fieldName, buildFieldErrorMessage(ife));
        }

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .errors(fieldErrors.isEmpty() ? null : fieldErrors)
                .build();

        return ResponseEntity.status(status).body(errorResponse);

    }

    //============ Rate Limit Handler =============
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(
            TooManyRequestsException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(exception.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity
                .status(status)
                .header("Retry-After", String.valueOf(exception.getRetryAfterSeconds()))
                .body(errorResponse);
    }

    //============ Outside provider (identity, bank) failed ============
    @ExceptionHandler(ProviderUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleProviderUnavailable(ProviderUnavailableException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(exception.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(status).body(response);
    }

    //============ Authentication Handler ============
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(exception.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity
                .status(status)
                .body(response);
    }

    //============ Access Denied Handler (@PreAuthorize failures) ============
    // Spring MVC's own exception resolution (this class) runs before the request would
    // otherwise reach Spring Security's ExceptionTranslationFilter/JwtAccessDeniedHandler,
    // so without this handler an authority mismatch was being caught by the generic
    // fallback below and misreported as a 500 instead of a 403.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.FORBIDDEN;

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message("You do not have permission to access this resource.")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity
                .status(status)
                .body(errorResponse);
    }

    //============ Generic Fallback Handler (Logs Trace Internally Only) ============
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception exception,
            HttpServletRequest request
    ) {
        // Full stack trace is logged to your server logs for debugging, never sent to the client
        log.error("Unhandled exception at [{} {}]: {}", request.getMethod(), request.getRequestURI(), exception.getMessage(), exception);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message("An unexpected error occurred. Please try again later.")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity
                .status(status)
                .body(errorResponse);
    }

    //============ Helper Methods ============

    private String buildFieldErrorMessage(InvalidFormatException ife) {
        Class<?> targetType = ife.getTargetType();
        Object invalidValue = ife.getValue();

        if (targetType != null && targetType.isEnum()) {
            String allowedValues = Arrays.stream(targetType.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return String.format("Invalid value '%s'. Allowed values are: [%s].", invalidValue, allowedValues);
        }

        if (targetType != null && (Number.class.isAssignableFrom(targetType) || targetType.isPrimitive())) {
            return String.format("Invalid value '%s'. Expected a valid number.", invalidValue);
        }

        return String.format("Invalid value '%s'.", invalidValue);
    }

}

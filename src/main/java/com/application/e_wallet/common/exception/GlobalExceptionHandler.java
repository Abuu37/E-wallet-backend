package com.application.e_wallet.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    //============  Your existing duplicate resource handler ============

    @ExceptionHandler(DuplicationResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(
            DuplicationResourceException exception,
            HttpServletRequest request
    ) {

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

    //============ Validation Field Error Handler =============

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
}

package com.medflow.orchestrator.exception;

import com.medflow.orchestrator.dto.ApiProblem;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String BASE_URI = "https://medflow.io/errors/";

    @ExceptionHandler(SlotNotAvailableException.class)
    public ResponseEntity<ApiProblem> handleSlotNotAvailable(
            SlotNotAvailableException ex, WebRequest request) {
        log.warn("Slot booking conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem("slot-not-available", "Slot Not Available",
                        HttpStatus.CONFLICT.value(), ex.getMessage(), request));
    }

    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<ApiProblem> handleNotFound(
            AppointmentNotFoundException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem("appointment-not-found", "Appointment Not Found",
                        HttpStatus.NOT_FOUND.value(), ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {
        String details = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(problem("validation-error", "Validation Failed",
                        HttpStatus.BAD_REQUEST.value(), details, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleGeneral(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem("internal-error", "Internal Server Error",
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "An unexpected error occurred.", request));
    }

    private ApiProblem problem(String type, String title, int status, String detail, WebRequest request) {
        return new ApiProblem(
                BASE_URI + type, title, status, detail,
                request.getDescription(false).replace("uri=", ""),
                MDC.get("correlationId"),
                Instant.now()
        );
    }
}

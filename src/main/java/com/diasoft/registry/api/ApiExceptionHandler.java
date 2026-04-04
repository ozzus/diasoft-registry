package com.diasoft.registry.api;

import com.diasoft.registry.service.BadRequestException;
import com.diasoft.registry.service.NotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException validationException) {
            FieldError fieldError = validationException.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
            String message = fieldError == null ? "validation failed" : fieldError.getField() + ": " + fieldError.getDefaultMessage();
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}

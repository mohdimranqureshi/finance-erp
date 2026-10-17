package com.financeerp.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, String> badRequest(IllegalArgumentException ex) {
		return Map.of("error", "BAD_REQUEST", "message", ex.getMessage());
	}

	@ExceptionHandler(DuplicateKeyException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public Map<String, String> duplicate(DuplicateKeyException ex) {
		return Map.of("error", "CONFLICT", "message", "A record with the same unique key already exists.");
	}

	@ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public Map<String, String> integrity(org.springframework.dao.DataIntegrityViolationException ex) {
		return Map.of("error", "CONFLICT", "message",
				"The request conflicts with an existing record or accounting constraint.");
	}

	@ExceptionHandler(AccessDeniedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public Map<String, String> forbidden(AccessDeniedException ex) {
		return Map.of("error", "FORBIDDEN", "message", ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, String> invalid(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream().findFirst().map(
				e -> e.getField() + ": " + (e.getDefaultMessage() == null ? "invalid value" : e.getDefaultMessage()))
				.orElse("Request validation failed.");
		return Map.of("error", "VALIDATION_ERROR", "message", message);
	}
}

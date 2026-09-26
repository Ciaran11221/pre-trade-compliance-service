package io.github.ciaran11221.compliance.web;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.core.JacksonException;

/**
 * A body Jackson cannot read (broken JSON, or "quantity": "abc") fails before any controller code
 * runs, so it never reaches the per-package validation. This turns it into the same 400 shape as
 * every other bad field. Extending ResponseEntityExceptionHandler makes Spring Boot's own
 * problem-details handler (spring.mvc.problemdetails.enabled) step aside; a plain advice with an
 * {@code @ExceptionHandler} for the same exception would lose to it.
 *
 * <p>
 * Known limit: Spring reads the body before {@code @PreAuthorize} runs, so a caller with the wrong
 * role who sends unreadable JSON gets this 400 rather than 403. The 400 says only which field of
 * the caller's own JSON did not parse; a well-formed body with bad values still gets 403 first.
 */
@RestControllerAdvice
class BadBodyHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ErrorResponseException problem = ApiProblems
			.badRequest(List.of(new ApiProblems.FieldError(field(ex), "could not be read as JSON of the expected type.")));
		return handleExceptionInternal(problem, problem.getBody(), headers, problem.getStatusCode(), request);
	}

	private static String field(HttpMessageNotReadableException ex) {
		if (ex.getCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
			String path = jackson.getPath()
				.stream()
				.map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
				.collect(Collectors.joining("."));
			if (!path.isEmpty()) {
				return path;
			}
		}
		return "body";
	}

}

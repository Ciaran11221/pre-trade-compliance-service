package io.github.ciaran11221.compliance.limits;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import io.github.ciaran11221.compliance.web.ApiProblems;

/**
 * Every error this package's controller can produce, as application/problem+json: Spring resolves
 * an ErrorResponseException into that body automatically, the same RFC 7807 shape the security
 * layer's own handlers write by hand for 401/403 (see ProblemDetailAccessDeniedHandler).
 * BadRequest delegates to the shared ApiProblems.badRequest which produces an errors[] array.
 */
final class LimitChangeProblems {

	private LimitChangeProblems() {
	}

	record FieldError(String field, String message) {
	}

	static ErrorResponseException problem(HttpStatus status, String title, String detail) {
		ProblemDetail problemDetail = ProblemDetail.forStatus(status);
		problemDetail.setTitle(title);
		problemDetail.setDetail(detail);
		return new ErrorResponseException(status, problemDetail, null);
	}

	static ErrorResponseException badRequest(List<FieldError> errors) {
		List<ApiProblems.FieldError> apiErrors = errors.stream()
			.map(e -> new ApiProblems.FieldError(e.field(), e.message()))
			.toList();
		return ApiProblems.badRequest(apiErrors);
	}

	static ErrorResponseException unprocessable(String detail) {
		return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable request", detail);
	}

	static ErrorResponseException forbidden(String detail) {
		return problem(HttpStatus.FORBIDDEN, "Forbidden", detail);
	}

	static ErrorResponseException conflict(String detail) {
		return problem(HttpStatus.CONFLICT, "Conflict", detail);
	}

	static ErrorResponseException notFound(String detail) {
		return problem(HttpStatus.NOT_FOUND, "Not found", detail);
	}

}

package io.github.ciaran11221.compliance.orders;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import io.github.ciaran11221.compliance.web.ApiProblems;

/**
 * Every error the orders package's controller can produce, as application/problem+json -- same
 * pattern as limits.LimitChangeProblems. BadRequest delegates to the shared ApiProblems.badRequest
 * which produces an errors[] array with {field, message} entries per spec 3.6.
 */
final class OrdersProblems {

	private OrdersProblems() {
	}

	record FieldError(String field, String message) {
	}

	private static ErrorResponseException problem(HttpStatus status, String title, String detail) {
		ProblemDetail problemDetail = ProblemDetail.forStatus(status);
		problemDetail.setTitle(title);
		problemDetail.setDetail(detail);
		return new ErrorResponseException(status, problemDetail, null);
	}

	/**
	 * 400 naming each bad field as an errors[] array: delegates to the shared web.ApiProblems
	 * which creates a ProblemDetail with a proper errors property.
	 */
	static ErrorResponseException badRequest(List<FieldError> errors) {
		List<ApiProblems.FieldError> apiErrors = errors.stream()
			.map(e -> new ApiProblems.FieldError(e.field(), e.message()))
			.toList();
		return ApiProblems.badRequest(apiErrors);
	}

	static ErrorResponseException notFound(String detail) {
		return problem(HttpStatus.NOT_FOUND, "Not found", detail);
	}

	static ErrorResponseException conflict(String detail) {
		return problem(HttpStatus.CONFLICT, "Conflict", detail);
	}

}

package io.github.ciaran11221.compliance.reference;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import io.github.ciaran11221.compliance.web.ApiProblems;

/**
 * Every error this package's controller can produce, as application/problem+json -- same pattern
 * as limits.LimitChangeProblems and orders.OrdersProblems. 403 needs no case here: @PreAuthorize's
 * own ProblemDetailAccessDeniedHandler already renders that denial the same way. BadRequest
 * delegates to the shared ApiProblems.badRequest which produces an errors[] array.
 */
final class ReferenceProblems {

	private ReferenceProblems() {
	}

	record FieldError(String field, String message) {
	}

	private static ErrorResponseException problem(HttpStatus status, String title, String detail) {
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

	static ErrorResponseException notFound(String detail) {
		return problem(HttpStatus.NOT_FOUND, "Not found", detail);
	}

}

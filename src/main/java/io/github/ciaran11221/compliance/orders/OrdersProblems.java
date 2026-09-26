package io.github.ciaran11221.compliance.orders;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Every error the orders package's controller can produce, as application/problem+json -- same
 * pattern as limits.LimitChangeProblems.
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
	 * 400 with errors[] {field, message}, per spec 3.6. Deep validation is M8; this is just enough
	 * that a missing or malformed field never reaches a NullPointerException instead.
	 */
	static ErrorResponseException badRequest(List<FieldError> errors) {
		ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problemDetail.setTitle("Bad request");
		problemDetail.setDetail("the request body failed validation.");
		problemDetail.setProperty("errors",
				errors.stream().map(e -> Map.of("field", e.field(), "message", e.message())).toList());
		return new ErrorResponseException(HttpStatus.BAD_REQUEST, problemDetail, null);
	}

	static ErrorResponseException notFound(String detail) {
		return problem(HttpStatus.NOT_FOUND, "Not found", detail);
	}

	static ErrorResponseException conflict(String detail) {
		return problem(HttpStatus.CONFLICT, "Conflict", detail);
	}

}

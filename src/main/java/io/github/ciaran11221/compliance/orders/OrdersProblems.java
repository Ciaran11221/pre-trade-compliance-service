package io.github.ciaran11221.compliance.orders;

import java.util.List;
import java.util.stream.Collectors;

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
	 * 400 naming each bad field, per spec 3.6's errors[] {field, message} shape -- folded into
	 * detail as one string rather than a ProblemDetail.setProperty("errors", ...) list: every other
	 * error in this codebase (limits.LimitChangeProblems included) renders through the same
	 * ErrorResponseException -> DispatcherServlet content-negotiation path with a plain detail
	 * string and is exercised by JwtAuthenticationTest; a custom "errors" property is untested
	 * territory there and not worth the risk for M8's out-of-scope deep validation. Deep
	 * field-by-field validation (a real errors[] array) is M8; this is just enough that a missing or
	 * malformed field never reaches a NullPointerException instead.
	 */
	static ErrorResponseException badRequest(List<FieldError> errors) {
		String detail = errors.stream().map(e -> e.field() + ": " + e.message()).collect(Collectors.joining("; "));
		return problem(HttpStatus.BAD_REQUEST, "Bad request", detail);
	}

	static ErrorResponseException notFound(String detail) {
		return problem(HttpStatus.NOT_FOUND, "Not found", detail);
	}

	static ErrorResponseException conflict(String detail) {
		return problem(HttpStatus.CONFLICT, "Conflict", detail);
	}

}

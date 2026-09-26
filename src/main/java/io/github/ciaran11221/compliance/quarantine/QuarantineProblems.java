package io.github.ciaran11221.compliance.quarantine;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Every error this package's controller can produce, as application/problem+json -- same pattern
 * as limits.LimitChangeProblems and orders.OrdersProblems.
 */
final class QuarantineProblems {

	private QuarantineProblems() {
	}

	private static ErrorResponseException problem(HttpStatus status, String title, String detail) {
		ProblemDetail problemDetail = ProblemDetail.forStatus(status);
		problemDetail.setTitle(title);
		problemDetail.setDetail(detail);
		return new ErrorResponseException(status, problemDetail, null);
	}

	static ErrorResponseException notFound(String detail) {
		return problem(HttpStatus.NOT_FOUND, "Not found", detail);
	}

	static ErrorResponseException forbidden(String detail) {
		return problem(HttpStatus.FORBIDDEN, "Forbidden", detail);
	}

	static ErrorResponseException conflict(String detail) {
		return problem(HttpStatus.CONFLICT, "Conflict", detail);
	}

}

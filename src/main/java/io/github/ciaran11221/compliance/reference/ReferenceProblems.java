package io.github.ciaran11221.compliance.reference;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Every error this package's controller can produce, as application/problem+json -- same pattern
 * as limits.LimitChangeProblems and orders.OrdersProblems. 403 needs no case here: @PreAuthorize's
 * own ProblemDetailAccessDeniedHandler already renders that denial the same way.
 */
final class ReferenceProblems {

	private ReferenceProblems() {
	}

	private static ErrorResponseException problem(HttpStatus status, String title, String detail) {
		ProblemDetail problemDetail = ProblemDetail.forStatus(status);
		problemDetail.setTitle(title);
		problemDetail.setDetail(detail);
		return new ErrorResponseException(status, problemDetail, null);
	}

	static ErrorResponseException badRequest(String detail) {
		return problem(HttpStatus.BAD_REQUEST, "Bad request", detail);
	}

	static ErrorResponseException notFound(String detail) {
		return problem(HttpStatus.NOT_FOUND, "Not found", detail);
	}

}

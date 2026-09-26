package io.github.ciaran11221.compliance.web;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Shared builder for 400 Bad Request responses across all packages. Creates a ProblemDetail with
 * an "errors" property containing a list of {field, message} records, one entry per bad field
 * from all packages' validation logic. Non-400 errors (404, 409, 422) remain in their respective
 * package's Problems class.
 */
public final class ApiProblems {

	private ApiProblems() {
	}

	public record FieldError(String field, String message) {
	}

	/**
	 * 400 Bad Request with an errors array listing every bad field and its validation message.
	 * ProblemDetail.setProperty("errors", list) serializes as a top-level errors array, one object
	 * per field.
	 */
	public static ErrorResponseException badRequest(List<FieldError> errors) {
		ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problemDetail.setTitle("Bad request");
		String detail = errors.size() == 1 ? "1 field is invalid" : errors.size() + " fields are invalid";
		problemDetail.setDetail(detail);
		problemDetail.setProperty("errors", errors);
		return new ErrorResponseException(HttpStatus.BAD_REQUEST, problemDetail, null);
	}

}

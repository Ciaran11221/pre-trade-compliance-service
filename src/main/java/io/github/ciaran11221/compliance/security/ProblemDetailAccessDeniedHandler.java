package io.github.ciaran11221.compliance.security;

import java.io.IOException;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Requirement 7: every 403 -- an unknown staff sub (requirement 4) or a role a @PreAuthorize
 * expression rejects -- gets the same RFC 7807 body, as application/problem+json. The exception
 * message becomes the ProblemDetail detail, which is how "unknown staff member" (requirement 4)
 * reaches the response; nothing here ever echoes the token.
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
		problem.setTitle("Forbidden");
		String detail = accessDeniedException.getMessage();
		problem.setDetail(detail == null || detail.isBlank() ? "You do not have permission to do that." : detail);
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType("application/problem+json");
		objectMapper.writeValue(response.getWriter(), problem);
	}

}

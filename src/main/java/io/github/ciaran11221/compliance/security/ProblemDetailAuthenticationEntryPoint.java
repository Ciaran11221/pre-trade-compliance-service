package io.github.ciaran11221.compliance.security;

import java.io.IOException;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Requirement 7: no token, a bad signature, an expired token or a malformed header all reach here
 * and all get the same RFC 7807 body -- title, status, detail -- as application/problem+json. The
 * token itself is never echoed back.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
		problem.setTitle("Unauthorized");
		problem.setDetail("A valid bearer token is required.");
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType("application/problem+json");
		objectMapper.writeValue(response.getWriter(), problem);
	}

}

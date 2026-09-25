package io.github.ciaran11221.compliance.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401/403 behaviour, the known-staff-only 403, /api/me itself, and health staying public.
 * anneWithTraderGetsHerOwnDetails is also the end-to-end check that a token signed with the
 * configured secret is accepted.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class JwtAuthenticationTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final String OTHER_SECRET = "a-completely-different-secret-nobody-agreed-on";

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@Test
	void healthIsPublicWithNoToken() {
		ResponseEntity<String> response = call("/actuator/health", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void noTokenIsUnauthorized() {
		ResponseEntity<String> response = call("/api/me", null);

		assertProblemDetail(response, HttpStatus.UNAUTHORIZED);
	}

	@Test
	void tokenSignedWithADifferentSecretIsUnauthorized() throws Exception {
		String token = TokenTool.signedToken("anne", List.of("TRADER"), 5, OTHER_SECRET);

		ResponseEntity<String> response = call("/api/me", token);

		assertProblemDetail(response, HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).doesNotContain(token);
	}

	@Test
	void expiredTokenIsUnauthorized() throws Exception {
		String token = TokenTool.signedToken("anne", List.of("TRADER"), -5, TEST_SECRET);

		ResponseEntity<String> response = call("/api/me", token);

		assertProblemDetail(response, HttpStatus.UNAUTHORIZED);
	}

	@Test
	void malformedAuthorizationHeaderIsUnauthorized() {
		ResponseEntity<String> response = restClient.method(HttpMethod.GET)
			.uri("http://localhost:" + port + "/api/me")
			.header(HttpHeaders.AUTHORIZATION, "not-a-bearer-token")
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		assertProblemDetail(response, HttpStatus.UNAUTHORIZED);
	}

	@Test
	void tokenForAnUnknownStaffIdIsForbiddenWithReason() throws Exception {
		String token = TokenTool.signedToken("nobody", List.of("TRADER"), 5, TEST_SECRET);

		ResponseEntity<String> response = call("/api/me", token);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
		assertThat(response.getBody()).contains("unknown staff member");
		assertThat(response.getBody()).doesNotContain(token);
	}

	@Test
	void anneWithTraderGetsHerOwnDetails() throws Exception {
		String token = TokenTool.signedToken("anne", List.of("TRADER"), 5, TEST_SECRET);

		ResponseEntity<String> response = call("/api/me", token);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"staffId\":\"anne\"");
		assertThat(response.getBody()).contains("\"team\":\"desk-a\"");
		assertThat(response.getBody()).contains("\"outOfOffice\":false");
	}

	@Test
	void unknownRoleNamesAreIgnoredNotErrors() throws Exception {
		String token = TokenTool.signedToken("anne", List.of("SPACE_PIRATE", "TRADER"), 5, TEST_SECRET);

		ResponseEntity<String> response = call("/api/me", token);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private ResponseEntity<String> call(String path, String token) {
		return restClient.get()
			.uri("http://localhost:" + port + path)
			.headers(headers -> {
				if (token != null) {
					headers.setBearerAuth(token);
				}
			})
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private void assertProblemDetail(ResponseEntity<String> response, HttpStatus status) {
		assertThat(response.getStatusCode()).isEqualTo(status);
		assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
		assertThat(response.getBody()).contains("\"status\":" + status.value());
	}

}

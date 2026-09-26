package io.github.ciaran11221.compliance.reference;

import java.time.Instant;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PUT /api/staff/{id}/out-of-office (spec 3.3/3.6, issue #14): "self, or SUPERVISOR" cannot be
 * expressed as a role in route-access.csv (see that file's row for this route and
 * StaffController's Javadoc), so RouteAccessMatrixTest's generic per-role sweep -- which always
 * substitutes a path variable that is never the caller's own id -- only ever proves the
 * SUPERVISOR-only half of this route's access rule. This test proves the other half directly:
 * self is allowed regardless of role, and a non-self, non-SUPERVISOR caller is still forbidden.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class StaffOutOfOfficeAccessTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	@LocalServerPort
	private int port;

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	private final RestClient restClient = RestClient.create();

	@BeforeEach
	void resetDatabase() {
		flyway.clean();
		flyway.migrate();
	}

	@Test
	void selfMaySetTheirOwnOutOfOfficeEvenAsAPlainTrader() throws Exception {
		ResponseEntity<String> response = put("brian", "brian", "TRADER", "2026-01-01T00:00:00Z",
				"2026-01-08T00:00:00Z");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		StaffView view = objectMapper.readValue(response.getBody(), StaffView.class);
		assertThat(view.outOfOfficeFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
		assertThat(view.outOfOfficeUntil()).isEqualTo(Instant.parse("2026-01-08T00:00:00Z"));
	}

	@Test
	void selfMayClearTheirOwnOutOfOffice() throws Exception {
		jdbcTemplate.update("UPDATE staff SET out_of_office_from = ?, out_of_office_until = ? WHERE id = 'brian'",
				java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
				java.sql.Timestamp.from(Instant.parse("2026-01-08T00:00:00Z")));

		ResponseEntity<String> response = put("brian", "brian", "TRADER", null, null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		StaffView view = objectMapper.readValue(response.getBody(), StaffView.class);
		assertThat(view.outOfOfficeFrom()).isNull();
		assertThat(view.outOfOfficeUntil()).isNull();
	}

	@Test
	void supervisorMaySetSomeoneElsesOutOfOffice() throws Exception {
		ResponseEntity<String> response = put("sup-1", "brian", "SUPERVISOR", "2026-01-01T00:00:00Z",
				"2026-01-08T00:00:00Z");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void aPlainTraderMayNotSetSomeoneElsesOutOfOffice() throws Exception {
		ResponseEntity<String> response = put("anne", "brian", "TRADER", "2026-01-01T00:00:00Z",
				"2026-01-08T00:00:00Z");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().getContentType())
			.as("every error is application/problem+json, never a generic error page")
			.isNotNull()
			.matches(contentType -> contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	private ResponseEntity<String> put(String actor, String targetId, String role, String from, String until)
			throws Exception {
		String token = TokenTool.signedToken(actor, List.of(role), 5, TEST_SECRET);
		String body = objectMapper.writeValueAsString(new OutOfOfficeRequestBody(from, until));
		return restClient.put()
			.uri("http://localhost:" + port + "/api/staff/" + targetId + "/out-of-office")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(body)
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

}

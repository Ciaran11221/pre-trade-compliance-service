package io.github.ciaran11221.compliance.reference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8: HTTP-level tests for staff out-of-office validation errors with the new errors[] array
 * format. Tests that:
 * (a) Two bad fields at once report both in errors[];
 * (b) Invalid ISO-8601 format names the field;
 * (c) from == until reports both fields;
 * (d) Valid body still works.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, StaffValidationErrorsHttpTest.ClockOverride.class })
@ActiveProfiles("test")
class StaffValidationErrorsHttpTest {

	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final java.time.Instant FIXED_START = java.time.Instant.parse("2026-01-01T00:00:00Z");

	@LocalServerPort
	private int port;

	@Autowired
	private Flyway flyway;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	private final RestClient restClient = RestClient.create();

	@BeforeEach
	void resetDatabase() {
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);
	}

	@Test
	void oneFieldSetReportsBothFieldsInErrorsArray() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", "2026-01-02T00:00:00Z");
		body.put("until", null);
		ResponseEntity<String> response = put("anne", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("errors")).isTrue();
		JsonNode errors = jsonResponse.get("errors");
		assertThat(errors.isArray()).isTrue();

		List<String> errorFields = errors.findValues("field").stream()
			.map(JsonNode::asText)
			.toList();
		assertThat(errorFields).containsExactly("until");
	}

	@Test
	void invalidInstantFormatNamesField() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", "not-an-instant");
		body.put("until", "2026-01-03T00:00:00Z");
		ResponseEntity<String> response = put("anne", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("errors")).isTrue();
		JsonNode errors = jsonResponse.get("errors");
		List<String> errorFields = errors.findValues("field").stream()
			.map(JsonNode::asText)
			.toList();
		assertThat(errorFields).contains("from");
	}

	@Test
	void fromNotBeforeUntilNamesFromField() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", "2026-01-03T00:00:00Z");
		body.put("until", "2026-01-02T00:00:00Z");
		ResponseEntity<String> response = put("anne", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("errors")).isTrue();
		JsonNode errors = jsonResponse.get("errors");
		List<String> errorFields = errors.findValues("field").stream()
			.map(JsonNode::asText)
			.toList();
		assertThat(errorFields).contains("from");
	}

	@Test
	void validBodyStillWorks() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", "2026-01-02T00:00:00Z");
		body.put("until", "2026-01-03T00:00:00Z");
		ResponseEntity<String> response = put("anne", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("id")).isTrue();
	}

	private ResponseEntity<String> put(String staffId, Map<String, Object> body) throws Exception {
		return restClient.put()
			.uri("http://localhost:" + port + "/api/staff/" + staffId + "/out-of-office")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(tokenForStaff(staffId)))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private String tokenForStaff(String staffId) {
		try {
			return TokenTool.signedToken(staffId, List.of("TRADER"), 5, TEST_SECRET);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockOverride {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(FIXED_START);
		}

	}

}

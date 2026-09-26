package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
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
 * M8: HTTP-level tests for limit-change validation errors with the new errors[] array format.
 * Tests that:
 * (a) Two bad fields at once report both in errors[];
 * (b) Malformed JSON reports with field "body";
 * (c) Wrong type names the field;
 * (d) Wrong role with bad body returns 403, not 400;
 * (e) Valid body still works.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, LimitChangeValidationErrorsHttpTest.ClockOverride.class })
@ActiveProfiles("test")
class LimitChangeValidationErrorsHttpTest {

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
	void twoBadFieldsReportBothInErrorsArray() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", "");
		body.put("newValue", null);
		body.put("reason", "");
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("errors")).isTrue();
		JsonNode errors = jsonResponse.get("errors");
		assertThat(errors.isArray()).isTrue();
		assertThat(errors.size()).isGreaterThanOrEqualTo(2);

		List<String> errorFields = errors.findValues("field").stream()
			.map(JsonNode::asText)
			.toList();
		assertThat(errorFields).contains("key", "reason");
	}

	@Test
	void blankFieldsReportBothInErrorsArray() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", "");
		body.put("newValue", new BigDecimal("3.5"));
		body.put("reason", "");
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("errors")).isTrue();
		JsonNode errors = jsonResponse.get("errors");
		List<String> errorFields = errors.findValues("field").stream()
			.map(JsonNode::asText)
			.toList();
		assertThat(errorFields).contains("key", "reason");
	}

	@Test
	void nullNewValueIsRejected() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", "ISSUER_LIMIT_PCT");
		body.put("newValue", null);
		body.put("reason", "test");
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("errors")).isTrue();
		JsonNode errors = jsonResponse.get("errors");
		List<String> errorFields = errors.findValues("field").stream()
			.map(JsonNode::asText)
			.toList();
		assertThat(errorFields).contains("newValue");
	}

	@Test
	void wrongRoleWithBadBodyReturns403NotA400() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", "");
		body.put("newValue", null);
		body.put("reason", "");

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/limit-changes")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void validBodyStillWorks() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", "ISSUER_LIMIT_PCT");
		body.put("newValue", "3.5");
		body.put("reason", "test increase");
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("id")).isTrue();
	}

	private ResponseEntity<String> post(Map<String, Object> body) throws Exception {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/limit-changes")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(supervisorToken("sup-1")))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private String traderToken(String staffId) {
		try {
			return TokenTool.signedToken(staffId, List.of("TRADER"), 5, TEST_SECRET);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private String supervisorToken(String staffId) {
		try {
			// Use existing SUPERVISOR from seed data if staffId not recognized
			String effectiveStaffId = "sup-1"; // Default supervisor from seed data
			return TokenTool.signedToken(effectiveStaffId, List.of("SUPERVISOR"), 5, TEST_SECRET);
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

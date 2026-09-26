package io.github.ciaran11221.compliance.orders;

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
 * M8: HTTP-level tests for order validation errors with the new errors[] array format. Tests that:
 * (a) Two bad fields at once report both in errors[];
 * (b) Malformed JSON reports with field "body";
 * (c) Wrong type (e.g. "quantity": "abc") names the field;
 * (d) Wrong role with bad body returns 403, not 400;
 * (e) Valid body still works.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, OrderValidationErrorsHttpTest.ClockOverride.class })
@ActiveProfiles("test")
class OrderValidationErrorsHttpTest {

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

	private long hgfFundId;

	@BeforeEach
	void resetDatabase() {
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);
		hgfFundId = jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = 'HGF'", Long.class);
	}

	@Test
	void twoBadFieldsReportBothInErrorsArray() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", "");
		body.put("fundId", hgfFundId);
		body.put("side", "SIDEWAYS");
		body.put("ticker", "KSTL");
		body.put("quantity", 10_000L);
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
		assertThat(errorFields).contains("clientOrderId", "side");
	}

	@Test
	void nullBodyReportsWithFieldBody() throws Exception {
		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.body("")
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorFields(response)).containsExactly("body");
	}

	@Test
	void malformedJsonReportsFieldBody() throws Exception {
		ResponseEntity<String> response = postRaw("{\"clientOrderId\": \"x\", ", traderToken("anne"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorFields(response)).containsExactly("body");
	}

	@Test
	void wrongTypeNamesTheField() throws Exception {
		String json = "{\"clientOrderId\": \"t1\", \"fundId\": " + hgfFundId
				+ ", \"side\": \"BUY\", \"ticker\": \"KSTL\", \"quantity\": \"abc\"}";
		ResponseEntity<String> response = postRaw(json, traderToken("anne"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorFields(response)).containsExactly("quantity");
	}

	/**
	 * Known limit, pinned so a change to it is seen: Spring reads the body before @PreAuthorize, so
	 * unreadable JSON from the wrong role is a 400, not a 403. Well-formed bad values still get 403
	 * (wrongRoleWithBadBodyReturns403NotA400).
	 */
	@Test
	void wrongRoleWithUnreadableJsonIsA400() throws Exception {
		ResponseEntity<String> response = postRaw("{not json", supervisorToken("sup-1"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorFields(response)).containsExactly("body");
	}

	@Test
	void blankFieldValuesReportedInErrorsArray() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", "");
		body.put("fundId", hgfFundId);
		body.put("side", "BUY");
		body.put("ticker", "");
		body.put("quantity", 10_000L);
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode jsonResponse = objectMapper.readTree(response.getBody());
		assertThat(jsonResponse.has("errors")).isTrue();
		JsonNode errors = jsonResponse.get("errors");
		List<String> errorFields = errors.findValues("field").stream()
			.map(JsonNode::asText)
			.toList();
		assertThat(errorFields).contains("clientOrderId", "ticker");
	}

	@Test
	void wrongRoleWithBadBodyReturns403NotA400() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", "");
		body.put("fundId", hgfFundId);
		body.put("side", "INVALID");
		body.put("ticker", "KSTL");
		body.put("quantity", 10_000L);

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(supervisorToken("sup-1")))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void validBodyStillWorks() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", "http-valid");
		body.put("fundId", hgfFundId);
		body.put("side", "BUY");
		body.put("ticker", "KSTL");
		body.put("quantity", 10_000L);
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		OrderView view = readView(response);
		assertThat(view.clientOrderId()).isEqualTo("http-valid");
	}

	private ResponseEntity<String> post(Map<String, Object> body) throws Exception {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> postRaw(String json, String token) {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(json)
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private List<String> errorFields(ResponseEntity<String> response) throws Exception {
		JsonNode errors = objectMapper.readTree(response.getBody()).get("errors");
		assertThat(errors).as("errors[] in %s", response.getBody()).isNotNull();
		return errors.findValues("field").stream().map(JsonNode::asText).toList();
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
			return TokenTool.signedToken(staffId, List.of("SUPERVISOR"), 5, TEST_SECRET);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private OrderView readView(ResponseEntity<String> response) throws Exception {
		return objectMapper.readValue(response.getBody(), OrderView.class);
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

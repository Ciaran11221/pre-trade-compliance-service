package io.github.ciaran11221.compliance.quarantine;

import java.time.Instant;
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

import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.orders.OrderView;
import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level tests for release/reject/list not already exercised by the scenario corpus
 * (S023-S032): a successful release actually runs the compliance rules and stores a decision
 * (reusing OrderService.decideAndRecord, not duplicating it -- issue #14's own wording), a
 * successful reject records REJECTED with no decision, GET /api/quarantine's list and
 * assignedToMe filter, and the two 409s (already resolved, already expired) content-type-asserted
 * as application/problem+json per this repo's "every error is RFC 7807" rule.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, QuarantineHttpTest.ClockOverride.class })
@ActiveProfiles("test")
class QuarantineHttpTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

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
	void releaseRunsTheComplianceRulesAndStoresADecision() throws Exception {
		OrderView anneOrder = readView(submit("release-http-a", "anne", "BUY", "KSTL", 1_000L));
		assertThat(anneOrder.status()).isEqualTo("PASS");

		OrderView brianOrder = readView(submit("release-http-b", "brian", "BUY", "KSTL", 1_050L));
		assertThat(brianOrder.status()).isEqualTo("QUARANTINED");
		assertThat(brianOrder.decision()).as("a quarantined order has no decision yet").isNull();

		ResponseEntity<String> releaseResponse = release(brianOrder.id(), "sup-1");
		assertThat(releaseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		OrderView released = readView(releaseResponse);
		assertThat(released.status()).as("release runs the engine: KSTL 1,050 sh comfortably passes")
			.isEqualTo("PASS");
		assertThat(released.decision()).as("release stores a decision the same way intake does").isNotNull();
		assertThat(released.decision().outcome()).isEqualTo("PASS");
		assertThat(released.quarantine()).as("the quarantine record itself is still visible after release")
			.isNotNull();

		Integer resolutionCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
						+ "WHERE q.order_id = ? AND qr.resolution = 'RELEASED' AND qr.resolved_by = 'sup-1'",
				Integer.class, brianOrder.id());
		assertThat(resolutionCount).isEqualTo(1);
	}

	@Test
	void rejectRecordsTheResolutionWithNoDecision() throws Exception {
		readView(submit("reject-http-a", "anne", "BUY", "KSTL", 1_000L));
		OrderView brianOrder = readView(submit("reject-http-b", "brian", "BUY", "KSTL", 1_050L));
		assertThat(brianOrder.status()).isEqualTo("QUARANTINED");

		ResponseEntity<String> rejectResponse = reject(brianOrder.id(), "sup-1");
		assertThat(rejectResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		OrderView rejected = readView(rejectResponse);
		assertThat(rejected.status()).isEqualTo("REJECTED");
		assertThat(rejected.decision()).isNull();
	}

	@Test
	void releaseAfterAlreadyResolvedIsConflictAsAProblemBody() throws Exception {
		readView(submit("double-resolve-a", "anne", "BUY", "KSTL", 1_000L));
		OrderView brianOrder = readView(submit("double-resolve-b", "brian", "BUY", "KSTL", 1_050L));

		ResponseEntity<String> first = reject(brianOrder.id(), "sup-1");
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> second = release(brianOrder.id(), "sup-2");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getHeaders().getContentType())
			.as("every error is application/problem+json, never a generic error page")
			.isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
	}

	@Test
	void releaseAfterExpiryIsConflict() throws Exception {
		OrderView brianOrder = readView(submit("expiry-release-a", "brian", "BUY", "KSTL", 1_000L, true));
		assertThat(brianOrder.status()).isEqualTo("QUARANTINED");

		clock.set(FIXED_START.plus(java.time.Duration.ofMinutes(31)));

		ResponseEntity<String> response = release(brianOrder.id(), "sup-1");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
	}

	@Test
	void listShowsOpenQuarantinesAndFiltersByAssignedToMe() throws Exception {
		readView(submit("list-http-a", "anne", "BUY", "KSTL", 1_000L));
		OrderView brianOrder = readView(submit("list-http-b", "brian", "BUY", "KSTL", 1_050L));
		assertThat(brianOrder.status()).isEqualTo("QUARANTINED");

		ResponseEntity<String> listResponse = list("sup-1", false);
		assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<QuarantineListItemView> all = objectMapper.readValue(listResponse.getBody(),
				objectMapper.getTypeFactory().constructCollectionType(List.class, QuarantineListItemView.class));
		assertThat(all).extracting(QuarantineListItemView::orderId).contains(brianOrder.id());

		// Nobody is out of office in this test, so escalation leaves assignedTo null (the general
		// SUPERVISOR pool is eligible) -- assignedToMe=true for sup-1 must therefore show nothing.
		ResponseEntity<String> mineResponse = list("sup-1", true);
		List<QuarantineListItemView> mine = objectMapper.readValue(mineResponse.getBody(),
				objectMapper.getTypeFactory().constructCollectionType(List.class, QuarantineListItemView.class));
		assertThat(mine).isEmpty();
	}

	private ResponseEntity<String> submit(String clientOrderId, String actor, String side, String ticker,
			long quantity) throws Exception {
		return submit(clientOrderId, actor, side, ticker, quantity, false);
	}

	private ResponseEntity<String> submit(String clientOrderId, String actor, String side, String ticker,
			long quantity, boolean actorIsOutOfOffice) throws Exception {
		if (actorIsOutOfOffice) {
			jdbcTemplate.update("UPDATE staff SET out_of_office_from = ?, out_of_office_until = ? WHERE id = ?",
					java.sql.Timestamp.from(Instant.parse("2025-12-01T00:00:00Z")),
					java.sql.Timestamp.from(Instant.parse("2026-02-01T00:00:00Z")), actor);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", clientOrderId);
		body.put("fundId", hgfFundId);
		body.put("side", side);
		body.put("ticker", ticker);
		body.put("quantity", quantity);
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token(actor, "TRADER")))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> release(long orderId, String actor) throws Exception {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/quarantine/" + orderId + "/release")
			.headers(h -> h.setBearerAuth(token(actor, "SUPERVISOR")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> reject(long orderId, String actor) throws Exception {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/quarantine/" + orderId + "/reject")
			.headers(h -> h.setBearerAuth(token(actor, "SUPERVISOR")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> list(String actor, boolean assignedToMe) throws Exception {
		return restClient.get()
			.uri("http://localhost:" + port + "/api/quarantine?assignedToMe=" + assignedToMe)
			.headers(h -> h.setBearerAuth(token(actor, "SUPERVISOR")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private String token(String staffId, String role) {
		try {
			return TokenTool.signedToken(staffId, List.of(role), 5, TEST_SECRET);
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

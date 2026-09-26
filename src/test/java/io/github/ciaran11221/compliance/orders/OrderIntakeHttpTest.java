package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;
import java.sql.Timestamp;
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

import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level tests for order intake, GET and fill/cancel, over the real Testcontainers Postgres
 * with real signed tokens -- same pattern as LimitChangeScenarioTest. Covers issue #13's done-when
 * items not already exercised by OrderIntakeIdempotencyTest or PendingExposureAndCashRaceTest:
 * basic submit/PASS, validation errors, restricted-list BLOCK plus its fill refusal, GET by id, GET
 * a fund's orders paged, fill/cancel transitions, and the decision-snapshot explainability check.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, OrderIntakeHttpTest.ClockOverride.class })
@ActiveProfiles("test")
class OrderIntakeHttpTest {

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

	// Fund A of spec 3.2's worked example: HGF already holds $45M of KSTL; a $10M buy takes it to
	// $55M (5.5%), joining the "over" group alongside NRTH+VLCN's existing $150M, for $205M = 20.5%
	// -- under the 25% bucket, so every rule (restricted-list, diversification, cash, order-size)
	// passes and the overall outcome is PASS.
	@Test
	void validOrderIsAcceptedAndDecided() throws Exception {
		ResponseEntity<String> response = submit("http-1", hgfFundId, "BUY", "KSTL", 100_000L);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		OrderView view = readView(response);
		assertThat(view.status()).isEqualTo("PASS");
		assertThat(view.decision()).isNotNull();
		assertThat(view.decision().outcome()).isEqualTo("PASS");
		assertThat(view.decision().ruleResults()).isNotEmpty();
		assertThat(view.clientOrderId()).isEqualTo("http-1");
		assertThat(view.fundId()).isEqualTo(hgfFundId);
		assertThat(view.ticker()).isEqualTo("KSTL");
		assertThat(view.quantity()).isEqualTo(100_000L);

		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE client_order_id = ?",
				Integer.class, "http-1");
		assertThat(rowCount).isEqualTo(1);
	}

	// A JSON body missing constructor properties fails to bind to the OrderRequestBody record at
	// all (Jackson rejects it before the controller method runs), which is Spring's own 400 rather
	// than OrdersProblems.badRequest -- still never a 500, which is all "just enough validation"
	// requires. OrderService.validate's own field-by-field checks are exercised below with a body
	// that binds fine but fails business validation (blank/invalid/non-positive values).
	@Test
	void bodyMissingRequiredKeysIsRejectedWith400NotA500() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", "http-missing-fields");
		// fundId, side, ticker, quantity all omitted.
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void invalidFieldValuesAreRejectedWith400NamingEachField() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", "");
		body.put("fundId", hgfFundId);
		body.put("side", "SIDEWAYS");
		body.put("ticker", "");
		body.put("quantity", -5);
		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("clientOrderId").contains("side").contains("ticker")
			.contains("quantity");
	}

	@Test
	void unknownFundIsRejectedWith404() throws Exception {
		ResponseEntity<String> response = submit("http-unknown-fund", 999_999L, "BUY", "KSTL", 1_000L);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void unknownTickerIsRejectedWith404() throws Exception {
		ResponseEntity<String> response = submit("http-unknown-ticker", hgfFundId, "BUY", "NOSUCH", 1_000L);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void restrictedSecurityIsBlockedAndCannotBeFilled() throws Exception {
		// ZPHR is R__seed.sql's one restricted security.
		ResponseEntity<String> response = submit("http-restricted", hgfFundId, "BUY", "ZPHR", 1_000L);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		OrderView view = readView(response);
		assertThat(view.status()).isEqualTo("BLOCK");
		assertThat(view.decision().outcome()).isEqualTo("BLOCK");

		ResponseEntity<String> fillResponse = fill(view.id());
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void getOrderReturnsWhatWasStored() throws Exception {
		ResponseEntity<String> submitResponse = submit("http-get", hgfFundId, "BUY", "KSTL", 50_000L);
		OrderView submitted = readView(submitResponse);

		ResponseEntity<String> getResponse = restClient.get()
			.uri("http://localhost:" + port + "/api/orders/" + submitted.id())
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		OrderView fetched = readView(getResponse);
		assertThat(fetched.id()).isEqualTo(submitted.id());
		assertThat(fetched.status()).isEqualTo(submitted.status());
		assertThat(fetched.decision().outcome()).isEqualTo(submitted.decision().outcome());
	}

	@Test
	void listsAFundsOrdersNewestFirst() throws Exception {
		OrderView first = readView(submit("http-list-1", hgfFundId, "BUY", "KSTL", 10_000L));
		OrderView second = readView(submit("http-list-2", hgfFundId, "BUY", "KSTL", 10_000L));

		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + port + "/api/funds/" + hgfFundId + "/orders?page=0")
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		PagedOrders page = objectMapper.readValue(response.getBody(), PagedOrders.class);
		assertThat(page.totalElements()).isGreaterThanOrEqualTo(2);
		List<Long> ids = page.orders().stream().map(OrderView::id).toList();
		assertThat(ids).contains(first.id(), second.id());
		// Newest first: second was submitted after first.
		assertThat(ids.indexOf(second.id())).isLessThan(ids.indexOf(first.id()));
	}

	@Test
	void fillThenFillAgainIsConflict() throws Exception {
		OrderView order = readView(submit("http-fill", hgfFundId, "BUY", "KSTL", 10_000L));

		ResponseEntity<String> fillResponse = fill(order.id());
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(readView(fillResponse).status()).isEqualTo("FILLED");

		ResponseEntity<String> secondFill = fill(order.id());
		assertThat(secondFill.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		ResponseEntity<String> cancelAfterFill = cancel(order.id());
		assertThat(cancelAfterFill.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void cancelThenCancelAgainIsConflict() throws Exception {
		OrderView order = readView(submit("http-cancel", hgfFundId, "BUY", "KSTL", 10_000L));

		ResponseEntity<String> cancelResponse = cancel(order.id());
		assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(readView(cancelResponse).status()).isEqualTo("CANCELLED");

		ResponseEntity<String> secondCancel = cancel(order.id());
		assertThat(secondCancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	/**
	 * Issue #13: "A decision stores the settings and the fund state it used, so reading it back
	 * later explains it even after limits change." Inserts a new, later-dated ISSUER_LIMIT_PCT
	 * setting_value row directly (the same way a real limit-change activation would) and shows the
	 * already-decided order's stored snapshot is untouched, while GET /api/limits (the live value)
	 * has moved on.
	 */
	@Test
	void decisionSnapshotSurvivesALaterLimitChange() throws Exception {
		OrderView order = readView(submit("http-snapshot", hgfFundId, "BUY", "KSTL", 100_000L));
		BigDecimal snapshotIssuerLimit = new BigDecimal(String.valueOf(order.decision().settingsSnapshot().get("ISSUER_LIMIT_PCT")));
		assertThat(snapshotIssuerLimit).isEqualByComparingTo("5");

		jdbcTemplate.update(
				"INSERT INTO setting_value (setting_key, value, active_from, change_request_id) VALUES (?, ?, ?, NULL)",
				"ISSUER_LIMIT_PCT", new BigDecimal("4"), Timestamp.from(clock.instant()));

		ResponseEntity<String> getResponse = restClient.get()
			.uri("http://localhost:" + port + "/api/orders/" + order.id())
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		OrderView reread = readView(getResponse);
		BigDecimal rereadIssuerLimit = new BigDecimal(String.valueOf(reread.decision().settingsSnapshot().get("ISSUER_LIMIT_PCT")));
		assertThat(rereadIssuerLimit).as("a stored decision must keep reporting the setting it actually used")
			.isEqualByComparingTo("5");

		ResponseEntity<String> limitsResponse = restClient.get()
			.uri("http://localhost:" + port + "/api/limits")
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		@SuppressWarnings("unchecked")
		Map<String, Object> limits = objectMapper.readValue(limitsResponse.getBody(), Map.class);
		assertThat(new BigDecimal(String.valueOf(limits.get("ISSUER_LIMIT_PCT")))).as("the live limit has moved on")
			.isEqualByComparingTo("4");
	}

	private ResponseEntity<String> submit(String clientOrderId, long fundId, String side, String ticker, long quantity)
			throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", clientOrderId);
		body.put("fundId", fundId);
		body.put("side", side);
		body.put("ticker", ticker);
		body.put("quantity", quantity);
		return post(body);
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

	private ResponseEntity<String> fill(long orderId) throws Exception {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders/" + orderId + "/fill")
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> cancel(long orderId) throws Exception {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders/" + orderId + "/cancel")
			.headers(h -> h.setBearerAuth(traderToken("anne")))
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

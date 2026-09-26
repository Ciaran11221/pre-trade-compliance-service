package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Issue #13's cash-lock and pending-exposure done-when items. Each test builds its own fund and
 * security directly via JdbcTemplate (the same approach LimitChangeScenarioTest's seedFixture
 * uses), rather than the shared R__seed.sql funds, so the numbers are chosen to isolate exactly one
 * rule at a time: diversified=false takes the 75-5-10 rule out of play entirely, and quantities are
 * kept well under 10% of average daily volume so the order-size rule never turns a PASS into a
 * REVIEW. That leaves the cash rule as the only thing that can block, which is what both tests are
 * about.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, PendingExposureAndCashRaceTest.ClockOverride.class })
@ActiveProfiles("test")
class PendingExposureAndCashRaceTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	// 100,000 shares * $600 = $60,000,000: affordable alone against $100,000,000 cash, not
	// affordable twice ($120,000,000 > $100,000,000). avg_daily_volume of 10,000,000 keeps the
	// order-size rule's 10% threshold (1,000,000 shares) far above this quantity.
	private static final long RACE_QUANTITY = 100_000L;

	private static final BigDecimal RACE_PRICE = new BigDecimal("600.0000");

	private static final long RACE_AVG_DAILY_VOLUME = 10_000_000L;

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

	/**
	 * Two different buys for one fund, sent at the same moment, each affordable alone but not
	 * together: one PASS, one BLOCK on cash, never two PASS. Proves the fund lock (SELECT ... FOR
	 * UPDATE, held for the whole submit() transaction -- see OrderService.submit's Javadoc) makes
	 * the second order's pending-exposure read see the first order's already-committed decision.
	 */
	@Test
	void twoUnaffordableTogetherBuysProduceExactlyOnePassAndOneBlock() throws Exception {
		long fundId = insertRaceFund("CASHRACE1", new BigDecimal("100000000.0000"));
		// Two DIFFERENT securities, same price/quantity/value: the cash rule pools pending buys
		// fund-wide regardless of ticker (see CashRule), so the race is unaffected, but M7b's
		// lookback (issue #14) only ever compares orders on the SAME fund+security -- using one
		// ticker per order keeps this test about the fund lock/cash race, not about triggering
		// possible-duplicate quarantine on two orders that would otherwise look identical.
		insertRaceSecurity("RACEA");
		insertRaceSecurity("RACEA2");

		Map<String, Object> bodyA = orderBody("race-a", fundId, "BUY", "RACEA", RACE_QUANTITY);
		Map<String, Object> bodyB = orderBody("race-b", fundId, "BUY", "RACEA2", RACE_QUANTITY);

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		OrderView first;
		OrderView second;
		try {
			Future<ResponseEntity<String>> futureA = pool.submit(postWithBarrier(bodyA, barrier));
			Future<ResponseEntity<String>> futureB = pool.submit(postWithBarrier(bodyB, barrier));

			ResponseEntity<String> responseA = futureA.get(30, TimeUnit.SECONDS);
			ResponseEntity<String> responseB = futureB.get(30, TimeUnit.SECONDS);

			assertThat(responseA.getStatusCode()).as("order A's response: %s", responseA.getBody())
				.isEqualTo(HttpStatus.CREATED);
			assertThat(responseB.getStatusCode()).as("order B's response: %s", responseB.getBody())
				.isEqualTo(HttpStatus.CREATED);

			first = readView(responseA);
			second = readView(responseB);
		}
		finally {
			pool.shutdown();
		}

		List<String> outcomes = List.of(first.decision().outcome(), second.decision().outcome());
		assertThat(outcomes).as("exactly one PASS and one BLOCK, never two PASS: got %s", outcomes)
			.containsExactlyInAnyOrder("PASS", "BLOCK");

		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE fund_id = ?",
				Integer.class, fundId);
		assertThat(rowCount).isEqualTo(2);
	}

	/**
	 * Spec 3.1: pending exposure is a PASS order not yet filled or cancelled. A second order that
	 * cannot fit alongside a still-pending first order is BLOCKed. Once the first is filled, issue
	 * #21 makes fill genuinely move the cash (fund.cash -= quantity x price), so a THIRD order of the
	 * SAME size as A still correctly BLOCKs afterwards (A's $60,000,000 is really gone, not merely
	 * "freed up" the way a cancel frees a reservation -- see the cancel test below for that case). The
	 * invariant this proves instead: A's value is subtracted from availability exactly ONCE, never
	 * twice (once as the real cash debit, and AGAIN as if it were still pending) -- a smaller order
	 * that fits the genuinely-remaining $40,000,000 passes, which a double-count bug (treating A as
	 * both spent AND still pending) would wrongly BLOCK.
	 */
	@Test
	void pendingExposureStopsCountingOnceAnOrderIsFilled() throws Exception {
		long fundId = insertRaceFund("PENDFILL1", new BigDecimal("100000000.0000"));
		// Three DIFFERENT securities, same price, for the same reason as the concurrent race test
		// above: cash pools fund-wide, but M7b's lookback (issue #14) is keyed on fund+security, and
		// these three orders would otherwise look like the same order sent three times.
		insertRaceSecurity("RACEB1");
		insertRaceSecurity("RACEB2");
		insertRaceSecurity("RACEB3");

		OrderView orderA = readView(post(orderBody("pend-fill-a", fundId, "BUY", "RACEB1", RACE_QUANTITY)));
		assertThat(orderA.decision().outcome()).as("first order, alone, must fit under cash").isEqualTo("PASS");

		OrderView orderBlocked = readView(post(orderBody("pend-fill-b", fundId, "BUY", "RACEB2", RACE_QUANTITY)));
		assertThat(orderBlocked.decision().outcome()).as("second order must be blocked while A is still pending")
			.isEqualTo("BLOCK");

		ResponseEntity<String> fillResponse = fill(orderA.id());
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(readView(fillResponse).status()).isEqualTo("FILLED");

		// Half of RACE_QUANTITY: $30,000,000, well within the genuinely-remaining $40,000,000
		// ($100,000,000 - A's real $60,000,000 debit). A double-count bug (A still subtracted as
		// pending on top of the real cash debit) would compute available as $40,000,000 -
		// $60,000,000 = a negative number, wrongly BLOCKing even this smaller order.
		long thirdOrderQuantity = RACE_QUANTITY / 2;
		OrderView orderAfterFill = readView(post(orderBody("pend-fill-c", fundId, "BUY", "RACEB3", thirdOrderQuantity)));
		assertThat(orderAfterFill.decision().outcome())
			.as("A's value is subtracted from availability exactly once: real cash debit, never also pending")
			.isEqualTo("PASS");
	}

	@Test
	void pendingExposureStopsCountingOnceAnOrderIsCancelled() throws Exception {
		long fundId = insertRaceFund("PENDCANCEL1", new BigDecimal("100000000.0000"));
		insertRaceSecurity("RACEC1");
		insertRaceSecurity("RACEC2");
		insertRaceSecurity("RACEC3");

		OrderView orderA = readView(post(orderBody("pend-cancel-a", fundId, "BUY", "RACEC1", RACE_QUANTITY)));
		assertThat(orderA.decision().outcome()).isEqualTo("PASS");

		OrderView orderBlocked = readView(post(orderBody("pend-cancel-b", fundId, "BUY", "RACEC2", RACE_QUANTITY)));
		assertThat(orderBlocked.decision().outcome()).isEqualTo("BLOCK");

		ResponseEntity<String> cancelResponse = cancel(orderA.id());
		assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(readView(cancelResponse).status()).isEqualTo("CANCELLED");

		OrderView orderAfterCancel = readView(post(orderBody("pend-cancel-c", fundId, "BUY", "RACEC3", RACE_QUANTITY)));
		assertThat(orderAfterCancel.decision().outcome())
			.as("once A is cancelled, its cash is no longer reserved as pending exposure")
			.isEqualTo("PASS");
	}

	private long insertRaceFund(String code, BigDecimal cash) {
		jdbcTemplate.update("INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES (?, ?, ?, ?, false)",
				code, code + " fixture fund", new BigDecimal("10000000000.0000"), cash);
		return jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = ?", Long.class, code);
	}

	private void insertRaceSecurity(String ticker) {
		jdbcTemplate.update("""
				INSERT INTO security (ticker, name, issuer_name, price, voting_shares_outstanding, avg_daily_volume)
				VALUES (?, ?, ?, ?, 50000000, ?)
				""", ticker, ticker, ticker, RACE_PRICE, RACE_AVG_DAILY_VOLUME);
	}

	private Callable<ResponseEntity<String>> postWithBarrier(Map<String, Object> body, CyclicBarrier barrier) {
		return () -> {
			barrier.await(30, TimeUnit.SECONDS);
			return post(body);
		};
	}

	private Map<String, Object> orderBody(String clientOrderId, long fundId, String side, String ticker,
			long quantity) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", clientOrderId);
		body.put("fundId", fundId);
		body.put("side", side);
		body.put("ticker", ticker);
		body.put("quantity", quantity);
		return body;
	}

	private ResponseEntity<String> post(Map<String, Object> body) {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(signedToken("anne")))
			.body(writeJson(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> fill(long orderId) {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders/" + orderId + "/fill")
			.headers(h -> h.setBearerAuth(signedToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> cancel(long orderId) {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders/" + orderId + "/cancel")
			.headers(h -> h.setBearerAuth(signedToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private String signedToken(String staffId) {
		try {
			return TokenTool.signedToken(staffId, List.of("TRADER"), 5, TEST_SECRET);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private String writeJson(Map<String, Object> body) {
		return objectMapper.writeValueAsString(body);
	}

	private OrderView readView(ResponseEntity<String> response) {
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

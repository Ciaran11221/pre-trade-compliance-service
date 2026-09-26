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
 * Issue #21's done-when items: a fill applies to the fund's holdings and cash, under the same fund
 * lock order intake takes, and its effect is never counted twice against a later order. HTTP-level,
 * real signed tokens, real Testcontainers Postgres, Flyway clean+migrate per test -- same pattern as
 * OrderIntakeHttpTest and PendingExposureAndCashRaceTest.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, OrderFillHoldingsAndCashTest.ClockOverride.class })
@ActiveProfiles("test")
class OrderFillHoldingsAndCashTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	// R__seed.sql: HGF holds 450,000 KSTL @ $100.0000 ($45M) against $100,000,000.0000 cash.
	private static final BigDecimal HGF_STARTING_CASH = new BigDecimal("100000000.0000");

	private static final long HGF_KSTL_STARTING_HOLDING = 450_000L;

	private static final BigDecimal KSTL_PRICE = new BigDecimal("100.0000");

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

	/**
	 * Issue #21's first done-when item: buy 100,000 KSTL @ $100 for HGF, fill it: cash drops by
	 * exactly $10,000,000.00 and the KSTL holding grows by exactly 100,000 shares. BigDecimal
	 * throughout (trap #3): cash change is quantity x price with no intermediate rounding.
	 */
	@Test
	void buyFillMovesFundCashDownAndHoldingUpByExactlyQuantityTimesPrice() throws Exception {
		OrderView order = readView(submit("fill-buy-1", hgfFundId, "BUY", "KSTL", 100_000L));
		assertThat(order.decision().outcome()).isEqualTo("PASS");

		ResponseEntity<String> fillResponse = fill(order.id(), null);
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(readView(fillResponse).status()).isEqualTo("FILLED");

		assertThat(fundCash(hgfFundId)).as("HGF cash after a $10,000,000.00 buy fill")
			.isEqualByComparingTo(HGF_STARTING_CASH.subtract(new BigDecimal("10000000.0000")));
		assertThat(holdingQuantity(hgfFundId, "KSTL")).as("KSTL holding after a 100,000-share buy fill")
			.isEqualTo(HGF_KSTL_STARTING_HOLDING + 100_000L);
	}

	/**
	 * Issue #21's second done-when item: after the fill above, KSTL is no longer a holding under 5%
	 * of HGF's assets (450,000 -> 550,000 shares, $45M -> $55M, above the $50M/5% threshold), so it
	 * now joins NRTH+VLCN in the diversification rule's pre-trade over-5% total. The SAME buy sent
	 * again is checked against that new holding: DiversificationRule.evaluate reports a pre-trade
	 * total of $205,000,000.00 (NRTH $75M + VLCN $75M + KSTL's now-over $55M) rather than the first
	 * order's $150,000,000.00 (NRTH+VLCN only, per OrderIntakeHttpTest's worked example).
	 */
	@Test
	void diversificationRuleReportsTheNewPreTradeTotalAfterAFill() throws Exception {
		OrderView firstOrder = readView(submit("fill-div-1", hgfFundId, "BUY", "KSTL", 100_000L));
		RuleResultView firstDiversification = diversificationResult(firstOrder);
		assertThat(firstDiversification.reason()).as("before any fill, KSTL is still under 5% and excluded")
			.contains("150,000,000.00");

		ResponseEntity<String> fillResponse = fill(firstOrder.id(), null);
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Advance well past LOOKBACK_MINUTES (5): otherwise the second, identical BUY KSTL order
		// would itself be quarantined as a POSSIBLE_DUPLICATE (issue #14) before the compliance
		// engine -- and therefore the diversification rule -- ever ran on it. That quarantine
		// behaviour is correct and untouched by this issue; this test is about diversification's
		// pre-trade total, so it sends the second order outside that window.
		clock.set(FIXED_START.plusSeconds(600));

		OrderView secondOrder = readView(submit("fill-div-2", hgfFundId, "BUY", "KSTL", 100_000L));
		RuleResultView secondDiversification = diversificationResult(secondOrder);
		assertThat(secondDiversification.reason())
			.as("after the fill, KSTL's now-larger holding is itself over 5% and joins the pre-trade total")
			.contains("205,000,000.00");
		assertThat(secondDiversification.measuredValue())
			.as("post-trade over-5% total is now $215,000,000.00 = 21.5% of HGF's $1B assets")
			.isEqualByComparingTo("21.5000");
		assertThat(secondOrder.decision().outcome()).as("21.5% is still under the 25% bucket limit")
			.isEqualTo("PASS");
	}

	/**
	 * Issue #21's price done-when item: a fill with an explicit price uses it instead of the order's
	 * reference_price, and the FILLED event's detail records the price actually used.
	 */
	@Test
	void fillWithAnExplicitPriceUsesItAndRecordsItInTheEventDetail() throws Exception {
		OrderView order = readView(submit("fill-price-1", hgfFundId, "BUY", "KSTL", 10_000L));
		assertThat(order.referencePrice()).isEqualByComparingTo(KSTL_PRICE);

		BigDecimal explicitPrice = new BigDecimal("105.5000");
		ResponseEntity<String> fillResponse = fill(order.id(), explicitPrice);
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		BigDecimal expectedValue = explicitPrice.multiply(BigDecimal.valueOf(10_000L));
		assertThat(fundCash(hgfFundId)).as("cash must move by quantity x the EXPLICIT price, not the reference price")
			.isEqualByComparingTo(HGF_STARTING_CASH.subtract(expectedValue));

		Map<String, Object> detail = filledEventDetail(order.id());
		assertThat(new BigDecimal(String.valueOf(detail.get("price")))).as("the event records the price actually used")
			.isEqualByComparingTo(explicitPrice);
		assertThat(new BigDecimal(String.valueOf(detail.get("cashBefore")))).isEqualByComparingTo(HGF_STARTING_CASH);
		assertThat(new BigDecimal(String.valueOf(detail.get("cashAfter"))))
			.isEqualByComparingTo(HGF_STARTING_CASH.subtract(expectedValue));
	}

	/**
	 * A price of zero or below is rejected before anything is touched, naming the field (design:
	 * "price must be > 0 else 400 ProblemDetail naming the field").
	 */
	@Test
	void fillWithANonPositivePriceIsRejectedWith400NamingTheField() throws Exception {
		OrderView order = readView(submit("fill-price-bad", hgfFundId, "BUY", "KSTL", 10_000L));

		ResponseEntity<String> fillResponse = fill(order.id(), BigDecimal.ZERO);
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fillResponse.getBody()).contains("price");

		assertThat(fundCash(hgfFundId)).as("a rejected fill must not touch cash").isEqualByComparingTo(HGF_STARTING_CASH);
		assertThat(orderStatus(order.id())).as("a rejected fill must not change the order's status")
			.isEqualTo("PASS");
	}

	/**
	 * Issue #21's SELL done-when item: a SELL fill adds quantity x price to cash and reduces the
	 * holding by quantity.
	 */
	@Test
	void sellFillIncreasesCashAndReducesTheHolding() throws Exception {
		OrderView order = readView(submit("fill-sell-1", hgfFundId, "SELL", "KSTL", 50_000L));
		assertThat(order.decision().outcome()).isIn("PASS", "REVIEW");

		ResponseEntity<String> fillResponse = fill(order.id(), null);
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(readView(fillResponse).status()).isEqualTo("FILLED");

		assertThat(fundCash(hgfFundId)).as("HGF cash after a $5,000,000.00 sell fill")
			.isEqualByComparingTo(HGF_STARTING_CASH.add(new BigDecimal("5000000.0000")));
		assertThat(holdingQuantity(hgfFundId, "KSTL")).as("KSTL holding after a 50,000-share sell fill")
			.isEqualTo(HGF_KSTL_STARTING_HOLDING - 50_000L);
	}

	/**
	 * Issue #21's oversell done-when item: a SELL fill that would take a holding below zero is
	 * refused with 409 and nothing changes -- neither cash nor the holding. HGF holds 450,000 KSTL;
	 * this sells 500,000. The compliance engine itself does not check holding sufficiency (only
	 * order-size/cash/restricted-list/diversification), so this decides REVIEW (order-size, since
	 * 500,000 shares is a large fraction of KSTL's average daily volume) rather than BLOCK, which is
	 * exactly the PASS/REVIEW-but-still-unfillable case this check exists for.
	 */
	@Test
	void sellBeyondTheHoldingIsConflictAndNothingChanges() throws Exception {
		OrderView order = readView(submit("fill-oversell-1", hgfFundId, "SELL", "KSTL", 500_000L));
		assertThat(order.decision().outcome()).as("the engine itself does not check holding sufficiency")
			.isIn("PASS", "REVIEW");

		ResponseEntity<String> fillResponse = fill(order.id(), null);
		assertThat(fillResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(fillResponse.getBody()).contains("450000").contains("KSTL").contains("500000");

		assertThat(fundCash(hgfFundId)).as("a refused sell must not touch cash").isEqualByComparingTo(HGF_STARTING_CASH);
		assertThat(holdingQuantity(hgfFundId, "KSTL")).as("a refused sell must not touch the holding")
			.isEqualTo(HGF_KSTL_STARTING_HOLDING);
		assertThat(orderStatus(order.id())).as("a refused fill must not change the order's status")
			.isEqualTo(order.decision().outcome());
	}

	/**
	 * Issue #21's no-double-count done-when item: once an order is filled, its cost lives in
	 * fund.cash (already reduced) rather than in the pending-buys total (findPendingOrders excludes
	 * FILLED orders), so the cash available to the next order is exactly fund.cash minus every OTHER
	 * still-pending buy -- never fund.cash minus pending buys that ALSO includes the filled order's
	 * own value a second time. Uses a dedicated fixture fund (diversified=false, so only the cash
	 * rule can block) to isolate the cash math from HGF's diversification numbers.
	 *
	 * <p>
	 * Order A (BUY $60,000,000) is filled, taking cash from $100,000,000 to $40,000,000. Order C
	 * (BUY $10,000,000) is left pending. Order D's value ($30,000,000) is chosen to exactly equal the
	 * CORRECT available amount ($40,000,000 cash - $10,000,000 pending C = $30,000,000): if A's value
	 * were double-counted (subtracted from cash by the fill AND still subtracted again as pending),
	 * available would be $40,000,000 - $60,000,000 - $10,000,000, a negative number, and D would
	 * wrongly BLOCK. D passing exactly at that boundary is the invariant this test proves.
	 */
	@Test
	void availableCashAfterAFillExcludesTheFilledOrderButStillCountsOtherPendingBuys() throws Exception {
		long fundId = insertFixtureFund("NODOUBLE1", new BigDecimal("100000000.0000"));
		insertFixtureSecurity("NDBL1");
		insertFixtureSecurity("NDBL2");
		insertFixtureSecurity("NDBL3");

		OrderView orderA = readView(post(orderBody("nodbl-a", fundId, "BUY", "NDBL1", 600_000L)));
		assertThat(orderA.decision().outcome()).as("A alone: $60,000,000 fits under $100,000,000").isEqualTo("PASS");

		ResponseEntity<String> fillA = fill(orderA.id(), null);
		assertThat(fillA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fundCash(fundId)).isEqualByComparingTo(new BigDecimal("40000000.0000"));

		OrderView orderC = readView(post(orderBody("nodbl-c", fundId, "BUY", "NDBL2", 100_000L)));
		assertThat(orderC.decision().outcome()).as("C alone against the reduced $40,000,000 cash").isEqualTo("PASS");

		OrderView orderD = readView(post(orderBody("nodbl-d", fundId, "BUY", "NDBL3", 300_000L)));
		assertThat(orderD.decision().outcome())
			.as("D's $30,000,000 must fit: $40,000,000 cash - $10,000,000 pending C, with A's cost never counted twice")
			.isEqualTo("PASS");
		RuleResultView cashResult = ruleResult(orderD, "cash");
		assertThat(cashResult.measuredValue()).isEqualByComparingTo("30000000.0000");
		assertThat(cashResult.limitValue()).as("available cash reported by the rule itself")
			.isEqualByComparingTo("30000000.0000");
	}

	/**
	 * Issue #21's race done-when item and trap #7: fill and a new order for the same fund take the
	 * SAME fund row lock, so they serialise rather than interleave. Fund cash starts at
	 * $100,000,000; order A (BUY $60,000,000) is already PASS and pending. Concurrently, one thread
	 * fills A (which will take cash to $40,000,000 and drop A from pending) while another submits new
	 * order B (BUY $50,000,000).
	 *
	 * <p>
	 * Invariant under test: B is NEVER decided PASS. Whichever thread's transaction the fund lock
	 * lets run first, B's read of "cash available" is at most $40,000,000 either way -- before A's
	 * fill commits, cash is still $100,000,000 but A's $60,000,000 counts as pending (available
	 * $40,000,000); after A's fill commits, cash is $40,000,000 and A no longer counts as pending
	 * (available $40,000,000). $50,000,000 exceeds that $40,000,000 in both cases. A bug that let B
	 * read fund.cash and pending exposure from inconsistent points in time (e.g. the fund lock
	 * removed from fill) could let B see the pre-fill $100,000,000 cash without A still counting as
	 * pending, wrongly reporting $100,000,000 available and passing -- double-counting the same
	 * $60,000,000 that A's own fill is concurrently spending.
	 */
	@Test
	void concurrentFillAndNewBuyForTheSameFundNeverLetTheNewBuyDoubleCountTheSameCash() throws Exception {
		long fundId = insertFixtureFund("RACEFILL1", new BigDecimal("100000000.0000"));
		insertFixtureSecurity("RACEFILLA");
		insertFixtureSecurity("RACEFILLB");

		OrderView orderA = readView(post(orderBody("racefill-a", fundId, "BUY", "RACEFILLA", 600_000L)));
		assertThat(orderA.decision().outcome()).isEqualTo("PASS");

		Map<String, Object> bodyB = orderBody("racefill-b", fundId, "BUY", "RACEFILLB", 500_000L);

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		ResponseEntity<String> fillResponse;
		ResponseEntity<String> submitBResponse;
		try {
			Future<ResponseEntity<String>> fillFuture = pool.submit(fillWithBarrier(orderA.id(), barrier));
			Future<ResponseEntity<String>> submitBFuture = pool.submit(postWithBarrier(bodyB, barrier));

			fillResponse = fillFuture.get(30, TimeUnit.SECONDS);
			submitBResponse = submitBFuture.get(30, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdown();
		}

		assertThat(fillResponse.getStatusCode()).as("fill response: %s", fillResponse.getBody())
			.isEqualTo(HttpStatus.OK);
		assertThat(submitBResponse.getStatusCode()).as("submit B response: %s", submitBResponse.getBody())
			.isEqualTo(HttpStatus.CREATED);

		OrderView orderB = readView(submitBResponse);
		assertThat(orderB.decision().outcome())
			.as("B's $50,000,000 must never pass: at most $40,000,000 is ever available, whichever ran first")
			.isEqualTo("BLOCK");

		assertThat(fundCash(fundId)).as("A's fill must still have gone through, taking cash to $40,000,000")
			.isEqualByComparingTo(new BigDecimal("40000000.0000"));
	}

	/**
	 * The test that fails when fill stops taking the fund lock. Fill reads cash and writes back
	 * cash minus the order's value; without the lock, two fills of two different orders on one fund
	 * both read the same starting cash and the second write overwrites the first (a lost update).
	 * Fund cash $200,000,000; orders A and B are $60,000,000 buys, both PASS. Filling them at the
	 * same moment must leave exactly $80,000,000. Without the lock this left $140,000,000 on 3 of 3
	 * runs during review. Repeated on fresh funds because one pair can miss the window.
	 */
	@Test
	void twoFillsForOneFundAtTheSameMomentBothComeOffCash() throws Exception {
		for (int attempt = 0; attempt < 5; attempt++) {
			long fundId = insertFixtureFund("TWOFILL" + attempt, new BigDecimal("200000000.0000"));
			insertFixtureSecurity("TWOFA" + attempt);
			insertFixtureSecurity("TWOFB" + attempt);
			OrderView orderA = readView(post(orderBody("twofill-a-" + attempt, fundId, "BUY", "TWOFA" + attempt, 600_000L)));
			OrderView orderB = readView(post(orderBody("twofill-b-" + attempt, fundId, "BUY", "TWOFB" + attempt, 600_000L)));
			assertThat(orderA.decision().outcome()).isEqualTo("PASS");
			assertThat(orderB.decision().outcome()).isEqualTo("PASS");

			CyclicBarrier barrier = new CyclicBarrier(2);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Future<ResponseEntity<String>> fillA = pool.submit(fillWithBarrier(orderA.id(), barrier));
				Future<ResponseEntity<String>> fillB = pool.submit(fillWithBarrier(orderB.id(), barrier));
				assertThat(fillA.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
				assertThat(fillB.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
			}
			finally {
				pool.shutdown();
			}

			assertThat(fundCash(fundId)).as("attempt %d: both $60,000,000 fills must come off $200,000,000", attempt)
				.isEqualByComparingTo(new BigDecimal("80000000.0000"));
		}
	}

	private RuleResultView diversificationResult(OrderView order) {
		return ruleResult(order, "diversification");
	}

	private RuleResultView ruleResult(OrderView order, String ruleName) {
		return order.decision()
			.ruleResults()
			.stream()
			.filter(r -> r.ruleName().equals(ruleName))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no " + ruleName + " rule result on order " + order.id()));
	}

	private BigDecimal fundCash(long fundId) {
		return jdbcTemplate.queryForObject("SELECT cash FROM fund WHERE id = ?", BigDecimal.class, fundId);
	}

	private long holdingQuantity(long fundId, String ticker) {
		return jdbcTemplate.queryForObject("""
				SELECT h.quantity FROM holding h JOIN security s ON s.id = h.security_id
				WHERE h.fund_id = ? AND s.ticker = ?
				""", Long.class, fundId, ticker);
	}

	/** The order's current status exactly as GET /api/orders/{id} reports it (OrderService.deriveStatus). */
	private String orderStatus(long orderId) throws Exception {
		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + port + "/api/orders/" + orderId)
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		return readView(response).status();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> filledEventDetail(long orderId) throws Exception {
		String json = jdbcTemplate.queryForObject("""
				SELECT detail::text FROM order_event WHERE order_id = ? AND event_type = 'FILLED'
				""", String.class, orderId);
		return objectMapper.readValue(json, Map.class);
	}

	private long insertFixtureFund(String code, BigDecimal cash) {
		jdbcTemplate.update("INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES (?, ?, ?, ?, false)",
				code, code + " fixture fund", new BigDecimal("10000000000.0000"), cash);
		return jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = ?", Long.class, code);
	}

	private void insertFixtureSecurity(String ticker) {
		// avg_daily_volume of 1,000,000,000 keeps the order-size rule's 10% threshold
		// (100,000,000 shares) far above every quantity these fixture tests trade.
		jdbcTemplate.update("""
				INSERT INTO security (ticker, name, issuer_name, price, voting_shares_outstanding, avg_daily_volume)
				VALUES (?, ?, ?, ?, 50000000, 1000000000)
				""", ticker, ticker, ticker, new BigDecimal("100.0000"));
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

	private ResponseEntity<String> submit(String clientOrderId, long fundId, String side, String ticker, long quantity)
			throws Exception {
		return post(orderBody(clientOrderId, fundId, side, ticker, quantity));
	}

	private ResponseEntity<String> post(Map<String, Object> body) {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(traderToken("anne")))
			.body(writeJson(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> fill(long orderId, BigDecimal price) {
		var spec = restClient.post()
			.uri("http://localhost:" + port + "/api/orders/" + orderId + "/fill")
			.headers(h -> h.setBearerAuth(traderToken("anne")));
		if (price != null) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("price", price);
			return spec.contentType(MediaType.APPLICATION_JSON)
				.body(writeJson(body))
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.headers(res.getHeaders())
					.body(res.bodyTo(String.class)));
		}
		return spec.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
			.headers(res.getHeaders())
			.body(res.bodyTo(String.class)));
	}

	private Callable<ResponseEntity<String>> fillWithBarrier(long orderId, CyclicBarrier barrier) {
		return () -> {
			barrier.await(30, TimeUnit.SECONDS);
			return fill(orderId, null);
		};
	}

	private Callable<ResponseEntity<String>> postWithBarrier(Map<String, Object> body, CyclicBarrier barrier) {
		return () -> {
			barrier.await(30, TimeUnit.SECONDS);
			return post(body);
		};
	}

	private String traderToken(String staffId) {
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

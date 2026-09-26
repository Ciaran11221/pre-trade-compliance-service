package io.github.ciaran11221.compliance.quarantine;

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

import io.github.ciaran11221.compliance.orders.OrderView;
import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trap #7 / issue #14's own concurrency done-when item: "a release and a new order for the same
 * fund, affordable separately but not together, must never both PASS." Real commits (no test
 * transaction wrapping both calls), a CyclicBarrier so both HTTP calls fire back to back, no sleep.
 *
 * <p>
 * Fund cash is $100,000,000. brian (out of office) is quarantined buying 100,000 RRQ @ $600 =
 * $60,000,000 -- open, so it already counts as pending exposure (spec 3.3 "d") before the race
 * starts. Concurrently: sup-1 releases brian's order, and anne submits a brand new 100,000 RRC @
 * $600 = $60,000,000 buy on the SAME fund. $60,000,000 + $60,000,000 = $120,000,000 exceeds the
 * $100,000,000 cash, so at most one of {brian's release, anne's new order} may end up PASS.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, QuarantineReleaseFundLockRaceTest.ClockOverride.class })
@ActiveProfiles("test")
class QuarantineReleaseFundLockRaceTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	private static final long RACE_QUANTITY = 100_000L;

	private static final BigDecimal RACE_PRICE = new BigDecimal("600.0000");

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

	private long fundId;

	@BeforeEach
	void resetDatabase() {
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);

		fundId = insertRaceFund("RELEASERACE1", new BigDecimal("100000000.0000"));
		insertRaceSecurity("RRQ");
		insertRaceSecurity("RRC");

		jdbcTemplate.update("UPDATE staff SET out_of_office_from = ?, out_of_office_until = ? WHERE id = 'brian'",
				java.sql.Timestamp.from(Instant.parse("2025-12-01T00:00:00Z")),
				java.sql.Timestamp.from(Instant.parse("2026-02-01T00:00:00Z")));
	}

	@Test
	void releaseAndANewOrderForTheSameFundNeverBothPass() throws Exception {
		OrderView quarantined = readView(submit("release-race-quarantine", "brian", "RRQ"));
		assertThat(quarantined.status()).isEqualTo("QUARANTINED");

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		ResponseEntity<String> releaseResponse;
		ResponseEntity<String> newOrderResponse;
		try {
			Future<ResponseEntity<String>> releaseFuture = pool
				.submit(releaseWithBarrier(quarantined.id(), barrier));
			Future<ResponseEntity<String>> submitFuture = pool
				.submit(submitWithBarrier("release-race-new-order", "anne", "RRC", barrier));

			releaseResponse = releaseFuture.get(30, TimeUnit.SECONDS);
			newOrderResponse = submitFuture.get(30, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdown();
		}

		assertThat(releaseResponse.getStatusCode()).as("release response: %s", releaseResponse.getBody())
			.isEqualTo(HttpStatus.OK);
		assertThat(newOrderResponse.getStatusCode()).as("new order response: %s", newOrderResponse.getBody())
			.isEqualTo(HttpStatus.CREATED);

		OrderView releasedView = readView(releaseResponse);
		OrderView newOrderView = readView(newOrderResponse);

		List<String> outcomes = List.of(releasedView.status(), newOrderView.status());
		boolean bothPass = "PASS".equals(releasedView.status()) && "PASS".equals(newOrderView.status());
		assertThat(bothPass)
			.as("release (%s) and the new order (%s) together would spend $120,000,000 against $100,000,000 cash; never both PASS",
					releasedView.status(), newOrderView.status())
			.isFalse();
		assertThat(outcomes).as("both must still resolve to a real outcome, never left hanging")
			.allMatch(status -> List.of("PASS", "BLOCK").contains(status));
	}

	private Callable<ResponseEntity<String>> releaseWithBarrier(long orderId, CyclicBarrier barrier) {
		return () -> {
			String token = token("sup-1", "SUPERVISOR");
			barrier.await(30, TimeUnit.SECONDS);
			return restClient.post()
				.uri("http://localhost:" + port + "/api/quarantine/" + orderId + "/release")
				.headers(h -> h.setBearerAuth(token))
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.headers(res.getHeaders())
					.body(res.bodyTo(String.class)));
		};
	}

	private Callable<ResponseEntity<String>> submitWithBarrier(String clientOrderId, String actor, String ticker,
			CyclicBarrier barrier) {
		return () -> {
			String token = token(actor, "TRADER");
			Map<String, Object> body = orderBody(clientOrderId, ticker);
			barrier.await(30, TimeUnit.SECONDS);
			return restClient.post()
				.uri("http://localhost:" + port + "/api/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.headers(h -> h.setBearerAuth(token))
				.body(objectMapper.writeValueAsString(body))
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.headers(res.getHeaders())
					.body(res.bodyTo(String.class)));
		};
	}

	private ResponseEntity<String> submit(String clientOrderId, String actor, String ticker) throws Exception {
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token(actor, "TRADER")))
			.body(objectMapper.writeValueAsString(orderBody(clientOrderId, ticker)))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private Map<String, Object> orderBody(String clientOrderId, String ticker) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", clientOrderId);
		body.put("fundId", fundId);
		body.put("side", "BUY");
		body.put("ticker", ticker);
		body.put("quantity", RACE_QUANTITY);
		return body;
	}

	private long insertRaceFund(String code, BigDecimal cash) {
		jdbcTemplate.update("INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES (?, ?, ?, ?, false)",
				code, code + " fixture fund", new BigDecimal("10000000000.0000"), cash);
		return jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = ?", Long.class, code);
	}

	private void insertRaceSecurity(String ticker) {
		jdbcTemplate.update("""
				INSERT INTO security (ticker, name, issuer_name, price, voting_shares_outstanding, avg_daily_volume)
				VALUES (?, ?, ?, ?, 50000000, 10000000)
				""", ticker, ticker, ticker, RACE_PRICE);
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

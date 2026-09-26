package io.github.ciaran11221.compliance.orders;

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
 * Issue #13's idempotency done-when items, over the real Testcontainers Postgres with real signed
 * tokens: same clientOrderId + same body replays the stored response and writes nothing; same
 * clientOrderId + a different body is a 409; and two threads racing an identical NEW clientOrderId,
 * with real commits (no @Transactional test rollback -- otherwise the two calls could never
 * actually race each other), produce exactly one trade_order row and two equal, non-500 responses.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, OrderIntakeIdempotencyTest.ClockOverride.class })
@ActiveProfiles("test")
class OrderIntakeIdempotencyTest {

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
	void sameClientOrderIdAndSameBodyReplaysTheStoredResponseAndWritesNothing() {
		Map<String, Object> body = orderBody("idem-1", hgfFundId, "BUY", "KSTL", 50_000L);

		ResponseEntity<String> first = post(body);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> second = post(body);
		assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
		assertThat(second.getBody()).as("a replay must return the exact stored response").isEqualTo(first.getBody());

		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE client_order_id = ?",
				Integer.class, "idem-1");
		assertThat(rowCount).as("a replay must not write a second order row").isEqualTo(1);
	}

	@Test
	void sameClientOrderIdWithADifferentBodyIsConflict() {
		ResponseEntity<String> first = post(orderBody("idem-2", hgfFundId, "BUY", "KSTL", 50_000L));
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> second = post(orderBody("idem-2", hgfFundId, "BUY", "KSTL", 60_000L));
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE client_order_id = ?",
				Integer.class, "idem-2");
		assertThat(rowCount).isEqualTo(1);
	}

	/**
	 * Real commits, no test-managed transaction: @Transactional on a @Test method would wrap the
	 * whole method (including both HTTP calls) in one rollback-only transaction on the TEST's own
	 * connection, which is not the connection either HTTP request's server-side transaction actually
	 * runs on -- it would prove nothing about the real race. Both threads submit the exact same new
	 * clientOrderId (never seen before this test) for the same fund, released together by a
	 * CyclicBarrier so the two POST calls fire back to back rather than one visibly finishing first.
	 */
	@Test
	void twoThreadsRacingTheSameNewClientOrderIdProduceExactlyOneRowAndEqualResponses() throws Exception {
		Map<String, Object> body = orderBody("idem-race-1", hgfFundId, "BUY", "KSTL", 50_000L);

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<ResponseEntity<String>> first = pool.submit(postWithBarrier(body, barrier));
			Future<ResponseEntity<String>> second = pool.submit(postWithBarrier(body, barrier));

			ResponseEntity<String> firstResponse = first.get(30, TimeUnit.SECONDS);
			ResponseEntity<String> secondResponse = second.get(30, TimeUnit.SECONDS);

			assertThat(firstResponse.getStatusCode()).as("first caller's response: %s", firstResponse.getBody())
				.isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(secondResponse.getStatusCode()).as("second caller's response: %s", secondResponse.getBody())
				.isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			assertThat(secondResponse.getBody()).as("both callers must get the same response")
				.isEqualTo(firstResponse.getBody());
		}
		finally {
			pool.shutdown();
		}

		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE client_order_id = ?",
				Integer.class, "idem-race-1");
		assertThat(rowCount).as("exactly one order row for the raced clientOrderId").isEqualTo(1);
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
		String token = signedToken("anne");
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(writeJson(body))
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
		try {
			return objectMapper.writeValueAsString(body);
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

package io.github.ciaran11221.compliance.quarantine;

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
 * Issue #14's other named concurrency done-when item: "no order is both released and rejected,
 * including two supervisors acting at the same moment." Real commits, a CyclicBarrier so both
 * HTTP calls fire back to back, no sleep -- same pattern as
 * limits.LimitChangeConcurrentApprovalTest and orders.OrderIntakeIdempotencyTest's race test.
 *
 * <p>
 * QuarantineService.lockOpenQuarantine takes {@code SELECT ... FOR UPDATE} on the quarantine row
 * before either supervisor's action proceeds: whichever transaction commits first writes the one
 * quarantine_resolution row (quarantine_id is UNIQUE), and the second -- unblocked only once the
 * first has committed and released the lock -- finds that resolution already there and gets a
 * clean 409, never a 500 and never a second resolution.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, QuarantineResolutionRaceTest.ClockOverride.class })
@ActiveProfiles("test")
class QuarantineResolutionRaceTest {

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
	void twoSupervisorsResolvingTheSameQuarantineAtOnceProduceExactlyOneResolution() throws Exception {
		readView(submit("resolution-race-a", "anne", "BUY", "KSTL", 1_000L));
		OrderView brianOrder = readView(submit("resolution-race-b", "brian", "BUY", "KSTL", 1_050L));
		assertThat(brianOrder.status()).isEqualTo("QUARANTINED");

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		ResponseEntity<String> releaseResponse;
		ResponseEntity<String> rejectResponse;
		try {
			Future<ResponseEntity<String>> releaseFuture = pool
				.submit(actionWithBarrier(brianOrder.id(), "sup-1", "release", barrier));
			Future<ResponseEntity<String>> rejectFuture = pool
				.submit(actionWithBarrier(brianOrder.id(), "sup-2", "reject", barrier));

			releaseResponse = releaseFuture.get(30, TimeUnit.SECONDS);
			rejectResponse = rejectFuture.get(30, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdown();
		}

		assertThat(releaseResponse.getStatusCode()).as("release response: %s", releaseResponse.getBody())
			.isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(rejectResponse.getStatusCode()).as("reject response: %s", rejectResponse.getBody())
			.isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		List<HttpStatus> statuses = List.of(HttpStatus.valueOf(releaseResponse.getStatusCode().value()),
				HttpStatus.valueOf(rejectResponse.getStatusCode().value()));
		assertThat(statuses).as("exactly one action wins (200), the other loses cleanly (409)")
			.containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);

		Integer resolutionCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
						+ "WHERE q.order_id = ?",
				Integer.class, brianOrder.id());
		assertThat(resolutionCount).as("exactly one resolution row, never both released and rejected").isEqualTo(1);
	}

	private Callable<ResponseEntity<String>> actionWithBarrier(long orderId, String actor, String action,
			CyclicBarrier barrier) {
		return () -> {
			String token = token(actor, "SUPERVISOR");
			barrier.await(30, TimeUnit.SECONDS);
			return restClient.post()
				.uri("http://localhost:" + port + "/api/quarantine/" + orderId + "/" + action)
				.headers(h -> h.setBearerAuth(token))
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.headers(res.getHeaders())
					.body(res.bodyTo(String.class)));
		};
	}

	private ResponseEntity<String> submit(String clientOrderId, String actor, String side, String ticker,
			long quantity) throws Exception {
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

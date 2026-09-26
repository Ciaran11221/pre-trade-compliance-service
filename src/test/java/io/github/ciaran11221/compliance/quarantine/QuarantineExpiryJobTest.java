package io.github.ciaran11221.compliance.quarantine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
 * QuarantineExpiryJob.runExpiry() called directly (issue #14: "test the job's method directly"),
 * never via @Scheduled -- QuarantineSchedulingConfig is @ConditionalOnProperty and disabled in
 * application-test.yml, so this bean exists in the test context but never fires on its own.
 * Writes the EXPIRED order_event + quarantine_resolution exactly once for an overdue, still-open
 * quarantine, and is idempotent (a second call finds nothing left to do).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, QuarantineExpiryJobTest.ClockOverride.class })
@ActiveProfiles("test")
class QuarantineExpiryJobTest {

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

	@Autowired
	private QuarantineExpiryJob quarantineExpiryJob;

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
	void writesExpiredOnceAndIsIdempotentOnASecondRun() throws Exception {
		jdbcTemplate.update("UPDATE staff SET out_of_office_from = ?, out_of_office_until = ? WHERE id = 'brian'",
				java.sql.Timestamp.from(Instant.parse("2025-12-01T00:00:00Z")),
				java.sql.Timestamp.from(Instant.parse("2026-02-01T00:00:00Z")));

		OrderView order = readView(submit("expiry-job-a", "brian"));
		assertThat(order.status()).isEqualTo("QUARANTINED");

		// Still well within the window: nothing to expire yet.
		int firstRunTooEarly = quarantineExpiryJob.runExpiry();
		assertThat(firstRunTooEarly).isEqualTo(0);
		assertNoResolutionYet(order.id());

		clock.set(FIXED_START.plus(Duration.ofMinutes(31)));

		int expiredCount = quarantineExpiryJob.runExpiry();
		assertThat(expiredCount).as("exactly one quarantine should be written as EXPIRED").isEqualTo(1);

		Integer resolutionRows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
						+ "WHERE q.order_id = ? AND qr.resolution = 'EXPIRED'",
				Integer.class, order.id());
		assertThat(resolutionRows).isEqualTo(1);

		Integer eventRows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM order_event WHERE order_id = ? AND event_type = 'EXPIRED'", Integer.class,
				order.id());
		assertThat(eventRows).isEqualTo(1);

		// Idempotent: running it again writes nothing more.
		int secondRun = quarantineExpiryJob.runExpiry();
		assertThat(secondRun).as("a second run must find nothing left to expire").isEqualTo(0);

		Integer resolutionRowsAfterSecondRun = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
						+ "WHERE q.order_id = ? AND qr.resolution = 'EXPIRED'",
				Integer.class, order.id());
		assertThat(resolutionRowsAfterSecondRun).as("idempotent: still exactly one resolution row")
			.isEqualTo(1);

		OrderView reread = readView(get(order.id()));
		assertThat(reread.status()).isEqualTo("EXPIRED");
	}

	private void assertNoResolutionYet(long orderId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
						+ "WHERE q.order_id = ?",
				Integer.class, orderId);
		assertThat(count).isEqualTo(0);
	}

	private ResponseEntity<String> submit(String clientOrderId, String actor) throws Exception {
		String body = objectMapper.writeValueAsString(java.util.Map.of("clientOrderId", clientOrderId, "fundId",
				hgfFundId, "side", "BUY", "ticker", "KSTL", "quantity", 1_000L));
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token(actor, "TRADER")))
			.body(body)
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> get(long orderId) {
		return restClient.get()
			.uri("http://localhost:" + port + "/api/orders/" + orderId)
			.headers(h -> h.setBearerAuth(token("anne", "TRADER")))
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

package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
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

import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 9 / the concurrency check the milestone asks for by name: two different approvers give the
 * final approval to the same tightening request at the same moment, over real HTTP, with real
 * commits -- no @Transactional test rollback, since that would only prove the two calls never
 * actually raced. A CyclicBarrier lines both threads up so their POST /approvals calls fire back
 * to back rather than one visibly finishing first; there is no sleep anywhere in this test.
 *
 * <p>
 * LimitChangeService.approve() takes {@code SELECT ... FOR UPDATE} on the request row
 * (LimitChangeRepository.lockRequest) before deciding whether to activate. Postgres serializes the
 * two transactions on that lock: whichever commits first creates the one activation row, and
 * maybeActivate() is written to be a no-op once an activation already exists, so the second
 * transaction -- which only proceeds once the first has committed and released the lock -- sees
 * that and inserts its own approval without a second activation. Both callers still get a normal,
 * successful response; this test is what would go red if that idempotency check were ever removed
 * or narrowed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, LimitChangeConcurrentApprovalTest.ClockOverride.class })
@ActiveProfiles("test")
class LimitChangeConcurrentApprovalTest {

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

	@Test
	void twoSimultaneousFinalApprovalsProduceExactlyOneActivation() throws Exception {
		// Own reset, even though this class gets its own Spring context (and so its own
		// Testcontainers Postgres): explicit is cheaper than relying on context-cache behaviour to
		// guarantee a clean, seeded database, and it costs one extra round trip.
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);

		long requestId = requestTightening();

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<ResponseEntity<String>> first = pool
				.submit(approveWithBarrier(requestId, "sup-2", List.of("SUPERVISOR"), barrier));
			Future<ResponseEntity<String>> second = pool
				.submit(approveWithBarrier(requestId, "brian", List.of("SUPERVISOR"), barrier));

			ResponseEntity<String> firstResponse = first.get(30, TimeUnit.SECONDS);
			ResponseEntity<String> secondResponse = second.get(30, TimeUnit.SECONDS);

			assertThat(firstResponse.getStatusCode().is2xxSuccessful())
				.as("first approver's response: %s", firstResponse.getBody())
				.isTrue();
			assertThat(secondResponse.getStatusCode().is2xxSuccessful())
				.as("second approver's response: %s", secondResponse.getBody())
				.isTrue();
		}
		finally {
			pool.shutdown();
		}

		Integer activationCount = jdbcTemplate
			.queryForObject("SELECT COUNT(*) FROM limit_change_activation WHERE request_id = ?", Integer.class,
					requestId);
		assertThat(activationCount).as("activation rows for request %s", requestId).isEqualTo(1);

		Integer approvalCount = jdbcTemplate
			.queryForObject("SELECT COUNT(*) FROM limit_change_approval WHERE request_id = ?", Integer.class,
					requestId);
		assertThat(approvalCount).as("both concurrent approvals should still be recorded").isEqualTo(2);
	}

	/**
	 * anne (desk-a) requests a tightening (1 approval required, per RequiredApprovalsCalculator).
	 * sup-2 and brian (both desk-b) are the two concurrent final approvers below, satisfying rule
	 * 5's different-team requirement regardless of which one Postgres lets through first.
	 */
	private long requestTightening() throws Exception {
		String token = TokenTool.signedToken("anne", List.of("SUPERVISOR"), 5, TEST_SECRET);
		String body = objectMapper.writeValueAsString(Map.of("key", "ISSUER_LIMIT_PCT", "newValue",
				new BigDecimal("4"), "reason", "tighten the issuer limit for the concurrency test"));

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/limit-changes")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(body)
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		assertThat(response.getStatusCode().is2xxSuccessful()).as("request creation: %s", response.getBody()).isTrue();
		LimitChangeView view = objectMapper.readValue(response.getBody(), LimitChangeView.class);
		assertThat(view.preview().requiredApprovals()).as("this test relies on a single required approval")
			.isEqualTo(1);
		return view.id();
	}

	private Callable<ResponseEntity<String>> approveWithBarrier(long requestId, String staffId, List<String> roles,
			CyclicBarrier barrier) {
		return () -> {
			String token = TokenTool.signedToken(staffId, roles, 5, TEST_SECRET);
			barrier.await(30, TimeUnit.SECONDS);
			return restClient.post()
				.uri("http://localhost:" + port + "/api/limit-changes/" + requestId + "/approvals")
				.headers(h -> h.setBearerAuth(token))
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.headers(res.getHeaders())
					.body(res.bodyTo(String.class)));
		};
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

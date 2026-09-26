package io.github.ciaran11221.compliance.quarantine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.ErrorResponseException;

import io.github.ciaran11221.compliance.orders.OrderRequestBody;
import io.github.ciaran11221.compliance.orders.OrderService;
import io.github.ciaran11221.compliance.orders.OrderView;
import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for M9 (issue #30 part c): no quarantined order ends up both released and
 * rejected, whatever order release/reject calls arrive in.
 *
 * QuarantineService.release()/reject() both funnel through insertResolutionIfAbsent, guarded by a
 * SELECT ... FOR UPDATE lock on the quarantine row (QuarantineRepository.lockQuarantineByOrderId)
 * plus a UNIQUE constraint on quarantine_resolution.quarantine_id -- see
 * QuarantineResolutionRaceTest, which checks this for one specific two-thread interleaving. This
 * property instead fuzzes the *sequence* of release/reject calls (arbitrary actors, arbitrary
 * verbs, arbitrary order, including the sender trying to act on their own order and the same actor
 * trying twice) against many independently-quarantined orders, and checks the invariant the
 * database itself enforces: at most one resolution row per quarantine, so it can never hold both a
 * RELEASED and a REJECTED row for the same order.
 *
 * A single @Test loops 1,000 cases. Each case creates its OWN pair of orders (via
 * OrderService.submit(), same fund/security/side and a quantity within SIMILARITY_PCT of the
 * first, exactly how QuarantineResolutionRaceTest triggers a POSSIBLE_DUPLICATE quarantine) so a
 * case never contends with another case's quarantine, and asserts the second order is QUARANTINED
 * before running its own release/reject sequence. Runs against ONE Spring context / Testcontainers
 * Postgres for the whole run, for the same reason as limits.LimitBoundsPropertyTest: resetting
 * between every one of 1,000 cases is not needed for an invariant that only depends on each case's
 * own, independently-created order pair. The database is still reset once before the @Test and
 * once after (@AfterEach), so the roughly 2,000 orders this test creates never leak into another
 * test class sharing this Spring context.
 *
 * The generator's first cases are deterministic: both senders, both verbs, both quantity-delta
 * extremes, the sender trying (and being refused) to resolve their own quarantine before a
 * different actor does, the same actor acting twice, and two different actors racing release
 * against reject in both orders -- so every one of those paths is guaranteed to run at least once,
 * not just left to chance. The remaining cases are random action sequences of length 1 to 4 (seeded
 * java.util.SplittableRandom, overridable with -Dm9.seed=N) drawing from the six seeded staff ids
 * this test uses as actors.
 *
 * Not covered: two threads racing on the very same call (see QuarantineResolutionRaceTest for
 * that), and the scheduled expiry job resolving a quarantine out from under a release/reject call
 * (see QuarantineExpiryJobTest).
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import({ TestcontainersConfig.class, QuarantineResolutionPropertyTest.ClockOverride.class })
@ActiveProfiles("test")
class QuarantineResolutionPropertyTest {

	private static final long SEED = Long.getLong("m9.seed", 300719L);

	private static final int CASE_COUNT = 1000;

	private static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	private static final List<String> ACTORS = List.of("anne", "brian", "sup-1", "sup-2", "sup-3", "comp-1");

	private final AtomicLong tryCounter = new AtomicLong();

	@Autowired
	private Flyway flyway;

	@Autowired
	private OrderService orderService;

	@Autowired
	private QuarantineService quarantineService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MutableClock clock;

	private long hgfFundId;

	@BeforeEach
	void resetDatabase() {
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);
		hgfFundId = jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = 'HGF'", Long.class);
	}

	@AfterEach
	void resetDatabaseAfter() {
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockOverride {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(FIXED_START);
		}

	}

	record Action(String actor, boolean release) {
	}

	record Case(String secondSubmitter, long quantityDelta, List<Action> actions) {
	}

	@Test
	void neverBothReleasedAndRejected() {
		List<Case> cases = generateCases();
		assertThat(cases).as("seed %d: generator must produce exactly %d cases", SEED, CASE_COUNT)
			.hasSize(CASE_COUNT);

		int releasedCount = 0;
		int rejectedCount = 0;

		for (int i = 0; i < cases.size(); i++) {
			Case c = cases.get(i);
			String label = "seed=" + SEED + " case=" + i + " " + c;
			long n = tryCounter.incrementAndGet();
			long baseQuantity = 1_000L;
			long secondQuantity = baseQuantity + c.quantityDelta();

			submit("prop-c-" + n + "-a", "anne", "BUY", "KSTL", baseQuantity);
			OrderView second = submit("prop-c-" + n + "-b", c.secondSubmitter(), "BUY", "KSTL", secondQuantity);
			assertThat(second.status()).as("%s: second order must be quarantined to exercise this property", label)
				.isEqualTo("QUARANTINED");

			for (Action action : c.actions()) {
				try {
					if (action.release()) {
						quarantineService.release(second.id(), action.actor());
					}
					else {
						quarantineService.reject(second.id(), action.actor());
					}
				}
				catch (ErrorResponseException expectedRefusal) {
					// The sender resolving their own quarantine, a second actor finding it already
					// resolved, etc. are exactly what the business rules are meant to refuse; only the
					// resolution-count invariant below is under test here.
				}
			}

			Integer resolutionCount = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
							+ "WHERE q.order_id = ?",
					Integer.class, second.id());
			assertThat(resolutionCount).as("%s: at most one resolution for order %s, never both released and rejected",
					label, second.id()).isLessThanOrEqualTo(1);

			Integer conflictingCount = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
							+ "WHERE q.order_id = ? AND qr.resolution IN ('RELEASED', 'REJECTED')",
					Integer.class, second.id());
			assertThat(conflictingCount)
				.as("%s: never both a RELEASED and a REJECTED resolution for order %s", label, second.id())
				.isLessThanOrEqualTo(1);

			List<String> resolutions = jdbcTemplate.queryForList(
					"SELECT qr.resolution FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
							+ "WHERE q.order_id = ? AND qr.resolution IN ('RELEASED', 'REJECTED')",
					String.class, second.id());
			if (resolutions.contains("RELEASED")) {
				releasedCount++;
			}
			else if (resolutions.contains("REJECTED")) {
				rejectedCount++;
			}
		}

		System.out.println("M9 part c counters: releasedCount=" + releasedCount + " rejectedCount=" + rejectedCount
				+ " of " + CASE_COUNT + " cases (seed=" + SEED + ")");

		assertThat(releasedCount)
			.as("seed %d: at least one of %d cases must end RELEASED (saw %d)", SEED, CASE_COUNT, releasedCount)
			.isGreaterThan(0);
		assertThat(rejectedCount)
			.as("seed %d: at least one of %d cases must end REJECTED (saw %d)", SEED, CASE_COUNT, rejectedCount)
			.isGreaterThan(0);
	}

	private OrderView submit(String clientOrderId, String submitterId, String side, String ticker, long quantity) {
		OrderRequestBody body = new OrderRequestBody(clientOrderId, hgfFundId, side, ticker, quantity);
		return orderService.submit(submitterId, body);
	}

	private List<Case> generateCases() {
		List<Case> cases = new ArrayList<>();
		addEdgeCases(cases);

		SplittableRandom random = new SplittableRandom(SEED);
		while (cases.size() < CASE_COUNT) {
			cases.add(randomCase(random));
		}
		return cases;
	}

	/**
	 * Both senders, both verbs, both quantity-delta extremes, the sender trying (and being refused)
	 * to resolve their own quarantine before someone else does, the same actor acting twice, and two
	 * different actors racing release against reject in both orders.
	 */
	private void addEdgeCases(List<Case> cases) {
		for (String submitter : List.of("anne", "brian")) {
			for (long delta : new long[] { -100, 0, 100 }) {
				cases.add(new Case(submitter, delta, List.of(new Action("sup-1", true))));
				cases.add(new Case(submitter, delta, List.of(new Action("sup-1", false))));
				// The sender tries to resolve their own quarantine first (refused), then a different
				// actor actually resolves it.
				cases.add(new Case(submitter, delta, List.of(new Action(submitter, true), new Action("sup-2", true))));
				cases.add(
						new Case(submitter, delta, List.of(new Action(submitter, false), new Action("sup-2", false))));
				// The same actor acts twice: the second call finds it already resolved.
				cases.add(new Case(submitter, delta,
						List.of(new Action("sup-1", true), new Action("sup-1", true))));
				// Two different actors race release against reject, both directions.
				cases.add(new Case(submitter, delta,
						List.of(new Action("sup-1", true), new Action("sup-2", false))));
				cases.add(new Case(submitter, delta,
						List.of(new Action("sup-1", false), new Action("sup-2", true))));
			}
		}
	}

	private Case randomCase(SplittableRandom random) {
		String submitter = random.nextBoolean() ? "anne" : "brian";
		// Kept well inside SIMILARITY_PCT's default 10% of a 1,000-share baseline (up to 100 either
		// way) so the second order reliably lands in quarantine as a POSSIBLE_DUPLICATE regardless of
		// which similarity value this run happens to generate.
		long delta = random.nextLong(-100, 101);

		int actionCount = random.nextInt(1, 5);
		List<Action> actions = new ArrayList<>();
		for (int i = 0; i < actionCount; i++) {
			actions.add(new Action(ACTORS.get(random.nextInt(ACTORS.size())), random.nextBoolean()));
		}

		return new Case(submitter, delta, actions);
	}

}

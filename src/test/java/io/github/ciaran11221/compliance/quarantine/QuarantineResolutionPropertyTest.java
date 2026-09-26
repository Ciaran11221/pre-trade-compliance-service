package io.github.ciaran11221.compliance.quarantine;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.ErrorResponseException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;

import io.github.ciaran11221.compliance.PreTradeComplianceServiceApplication;
import io.github.ciaran11221.compliance.orders.OrderRequestBody;
import io.github.ciaran11221.compliance.orders.OrderService;
import io.github.ciaran11221.compliance.orders.OrderView;
import io.github.ciaran11221.compliance.support.MutableClock;

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
 * Each try creates its OWN pair of orders (via OrderService.submit(), same fund/security/side and a
 * quantity within SIMILARITY_PCT of the first, exactly how QuarantineResolutionRaceTest triggers a
 * POSSIBLE_DUPLICATE quarantine) so that a try never contends with another try's quarantine. Runs
 * against ONE Spring context / Postgres container for the whole 1000-try property, for the same
 * reason as limits.LimitBoundsPropertyTest: flyway.clean() + migrate() per try was measured too slow
 * for 1000 real-database tries, and is not needed here since every try targets a fresh order pair.
 *
 * As in LimitBoundsPropertyTest, this starts its own Postgres container by hand (see that class's
 * Javadoc for why TestcontainersConfig's @ServiceConnection bean does not fire outside the Spring
 * TestContext framework) and wires it in through System properties, which outrank
 * application.yml's own spring.datasource.url default -- the developer's local `docker compose up
 * -d` Postgres that trap 8 warns a database test must never fall back to.
 */
class QuarantineResolutionPropertyTest {

	private static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	private static final List<String> ACTORS = List.of("anne", "brian", "sup-1", "sup-2", "sup-3", "comp-1");

	private static final PostgreSQLContainer POSTGRES = startPostgres();

	private static final ConfigurableApplicationContext CONTEXT = bootstrap();

	private static final OrderService ORDER_SERVICE = CONTEXT.getBean(OrderService.class);

	private static final QuarantineService QUARANTINE_SERVICE = CONTEXT.getBean(QuarantineService.class);

	private static final JdbcTemplate JDBC = CONTEXT.getBean(JdbcTemplate.class);

	private static final long HGF_FUND_ID = JDBC.queryForObject("SELECT id FROM fund WHERE code = 'HGF'", Long.class);

	private static final AtomicLong TRY_COUNTER = new AtomicLong();

	@SuppressWarnings("resource")
	private static PostgreSQLContainer startPostgres() {
		PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
		container.start();
		return container;
	}

	// See the class Javadoc: System properties, not SpringApplicationBuilder#properties(...), are
	// what actually outrank application.yml's spring.datasource.url default.
	private static ConfigurableApplicationContext bootstrap() {
		System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
		System.setProperty("spring.datasource.username", POSTGRES.getUsername());
		System.setProperty("spring.datasource.password", POSTGRES.getPassword());
		try {
			return new SpringApplicationBuilder(PreTradeComplianceServiceApplication.class, ClockOverride.class)
				.profiles("test")
				.run();
		}
		finally {
			System.clearProperty("spring.datasource.url");
			System.clearProperty("spring.datasource.username");
			System.clearProperty("spring.datasource.password");
		}
	}

	@AfterContainer
	static void tearDown() {
		CONTEXT.close();
		POSTGRES.stop();
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

	@Property(tries = 1000)
	void neverBothReleasedAndRejected(@ForAll("cases") Case c) {
		long n = TRY_COUNTER.incrementAndGet();
		long baseQuantity = 1_000L;
		long secondQuantity = baseQuantity + c.quantityDelta();

		submit("prop-c-" + n + "-a", "anne", "BUY", "KSTL", baseQuantity);
		OrderView second = submit("prop-c-" + n + "-b", c.secondSubmitter(), "BUY", "KSTL", secondQuantity);
		assertThat(second.status()).as("case %s: second order must be quarantined to exercise this property", c)
			.isEqualTo("QUARANTINED");

		for (Action action : c.actions()) {
			try {
				if (action.release()) {
					QUARANTINE_SERVICE.release(second.id(), action.actor());
				}
				else {
					QUARANTINE_SERVICE.reject(second.id(), action.actor());
				}
			}
			catch (ErrorResponseException expectedRefusal) {
				// The sender resolving their own quarantine, a second actor finding it already
				// resolved, etc. are exactly what the business rules are meant to refuse; only the
				// resolution-count invariant below is under test here.
			}
		}

		Integer resolutionCount = JDBC.queryForObject(
				"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
						+ "WHERE q.order_id = ?",
				Integer.class, second.id());
		assertThat(resolutionCount).as("case %s: at most one resolution for order %s, never both released and rejected",
				c, second.id()).isLessThanOrEqualTo(1);

		Integer conflictingCount = JDBC.queryForObject(
				"SELECT COUNT(*) FROM quarantine_resolution qr JOIN quarantine q ON q.id = qr.quarantine_id "
						+ "WHERE q.order_id = ? AND qr.resolution IN ('RELEASED', 'REJECTED')",
				Integer.class, second.id());
		assertThat(conflictingCount)
			.as("case %s: never both a RELEASED and a REJECTED resolution for order %s", c, second.id())
			.isLessThanOrEqualTo(1);
	}

	private OrderView submit(String clientOrderId, String submitterId, String side, String ticker, long quantity) {
		OrderRequestBody body = new OrderRequestBody(clientOrderId, HGF_FUND_ID, side, ticker, quantity);
		return ORDER_SERVICE.submit(submitterId, body);
	}

	@Provide
	Arbitrary<Case> cases() {
		Arbitrary<String> submitterArb = Arbitraries.of("anne", "brian");
		// Kept well inside SIMILARITY_PCT's default 10% of a 1,000-share baseline (up to 100 either
		// way) so the second order reliably lands in quarantine as a POSSIBLE_DUPLICATE regardless of
		// which similarity value this run happens to generate through jqwik's own edge cases.
		Arbitrary<Long> deltaArb = Arbitraries.longs().between(-100, 100);
		Arbitrary<Action> actionArb = Combinators.combine(Arbitraries.of(ACTORS), Arbitraries.of(true, false))
			.as(Action::new);
		Arbitrary<List<Action>> actionsArb = actionArb.list().ofMinSize(1).ofMaxSize(4);

		return Combinators.combine(submitterArb, deltaArb, actionsArb).as(Case::new);
	}

}

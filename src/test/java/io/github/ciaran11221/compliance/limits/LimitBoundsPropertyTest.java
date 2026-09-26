package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import io.github.ciaran11221.compliance.rules.LimitKey;
import io.github.ciaran11221.compliance.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for M9 (issue #30 part b): the active value of every firm-limit setting is never
 * past its hard bound, whatever sequence of limit-change requests, approvals, cancels and clock
 * moves is generated.
 *
 * Every LimitKey's hard bounds are static (declared in code, not settings), and the ONLY place a
 * setting_value row can ever be written is LimitChangeService.maybeActivate(), which only fires for
 * a request that already passed LimitKey.firstViolatedBound() back in requestChange(). This property
 * fuzzes the whole request / approve / cancel / clock-advance surface through the real Spring
 * service against a real Postgres, so a future change that weakens or bypasses that check (say, a
 * bad refactor of requestChange, or a new activation path that skips it) would show up here even
 * though nothing here assumes how that check is implemented.
 *
 * Runs against ONE Spring context and ONE Postgres container for the whole 1000-try property
 * (started once in a static field, not per try): flyway.clean() + migrate() between tries was
 * measured as too slow for 1000 real-database tries (see M9 investigation), and it is not needed
 * here anyway -- the property under test ("today's active value respects its bound") does not
 * depend on starting from a pristine database, only on every write along the way having gone
 * through the bound check. State (and the clock) is therefore allowed to accumulate across tries;
 * each try's assertion is still a check of the live system's current state.
 *
 * This test starts its own Postgres container directly with testcontainers rather than importing
 * TestcontainersConfig: TestcontainersConfig's @ServiceConnection bean is wired up by Spring's
 * TestContext framework (a ContextCustomizerFactory that only runs for @SpringBootTest /
 * SpringExtension-driven tests), which a manually built SpringApplicationBuilder context never goes
 * through. The container below is the same postgres:17-alpine image, started and torn down the same
 * way, wired in by hand through spring.datasource.* properties instead -- so, as trap 8 requires,
 * this test always talks to its own disposable container and never to a developer's local `docker
 * compose up -d` Postgres.
 */
class LimitBoundsPropertyTest {

	private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

	private static final List<String> STAFF = List.of("anne", "brian", "sup-1", "sup-2", "sup-3", "comp-1", "exec-1");

	private static final PostgreSQLContainer POSTGRES = startPostgres();

	private static final ConfigurableApplicationContext CONTEXT = bootstrap();

	private static final LimitChangeService SERVICE = CONTEXT.getBean(LimitChangeService.class);

	private static final MutableClock CLOCK = CONTEXT.getBean(MutableClock.class);

	private static PostgreSQLContainer startPostgres() {
		@SuppressWarnings("resource")
		PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
		container.start();
		return container;
	}

	// SpringApplicationBuilder#properties(...) registers DEFAULT properties (the lowest-priority
	// property source Spring Boot has), so it is silently overridden by application.yml's own
	// spring.datasource.url default (localhost:5432/compliance) -- which is exactly the developer's
	// `docker compose up -d` Postgres that trap 8 warns a database test must never fall back to.
	// Found by inspecting this test's own Flyway startup log, which showed it connecting to
	// port 5432, not the container's mapped port. System properties outrank application.yml, so
	// they are used here instead, and cleared immediately once the context is built (the DataSource
	// bean has already resolved them into a live connection pool by then; leaving them set would
	// otherwise leak into any later test class sharing this forked JVM).
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
			return new MutableClock(START);
		}

	}

	sealed interface Step {
	}

	record RequestStep(LimitKey key, String requesterId, BigDecimal newValue) implements Step {
	}

	record ApproveStep(int requestRef, String approverId) implements Step {
	}

	record CancelStep(int requestRef, String callerId) implements Step {
	}

	record AdvanceClockStep(long hours) implements Step {
	}

	@Property(tries = 1000)
	void activeValueNeverPastItsHardBound(@ForAll("stepSequences") List<Step> steps) {
		List<Long> requestIds = new ArrayList<>();

		for (Step step : steps) {
			try {
				apply(step, requestIds);
			}
			catch (ErrorResponseException expectedRefusal) {
				// requestChange / approve / cancel refusing an invalid or out-of-turn call is exactly
				// what the business rules are meant to do; only the *active value* invariant below is
				// under test here.
			}
		}

		Map<String, BigDecimal> active = SERVICE.activeLimits();
		for (LimitKey key : LimitKey.values()) {
			BigDecimal value = active.get(key.name());
			assertThat(key.firstViolatedBound(value))
				.as("%s's active value %s must respect its hard bounds after steps %s", key, value, steps)
				.isEmpty();
		}
	}

	// Plain instanceof, not a switch pattern: the project compiles at --release 17, where record
	// deconstruction patterns in switch are not available (they were finalized only in JDK 21).
	private void apply(Step step, List<Long> requestIds) {
		if (step instanceof RequestStep r) {
			LimitChangeRequestBody body = new LimitChangeRequestBody(r.key().name(), r.newValue(), "M9 property fuzz");
			LimitChangeView view = SERVICE.requestChange(r.requesterId(), body);
			requestIds.add(view.id());
		}
		else if (step instanceof ApproveStep a) {
			Long id = pick(requestIds, a.requestRef());
			if (id != null) {
				SERVICE.approve(id, a.approverId(), rolesOf(a.approverId()));
			}
		}
		else if (step instanceof CancelStep c) {
			Long id = pick(requestIds, c.requestRef());
			if (id != null) {
				SERVICE.cancel(id, c.callerId(), rolesOf(c.callerId()));
			}
		}
		else if (step instanceof AdvanceClockStep t) {
			CLOCK.set(CLOCK.instant().plus(t.hours(), ChronoUnit.HOURS));
		}
	}

	private Long pick(List<Long> requestIds, int ref) {
		if (requestIds.isEmpty()) {
			return null;
		}
		return requestIds.get(Math.floorMod(ref, requestIds.size()));
	}

	private List<String> rolesOf(String staffId) {
		return switch (staffId) {
			case "anne", "brian" -> List.of("TRADER");
			case "sup-1", "sup-2", "sup-3" -> List.of("SUPERVISOR");
			case "comp-1" -> List.of("COMPLIANCE");
			case "exec-1" -> List.of("EXECUTIVE");
			default -> throw new IllegalArgumentException("not a seeded staff id: " + staffId);
		};
	}

	@Provide
	Arbitrary<List<Step>> stepSequences() {
		return stepArb().list().ofMinSize(1).ofMaxSize(6);
	}

	private Arbitrary<Step> stepArb() {
		Arbitrary<Step> requestStep = Arbitraries.of(LimitKey.values())
			.flatMap(key -> Combinators.combine(Arbitraries.of(STAFF), valuesFor(key))
				.as((requester, value) -> (Step) new RequestStep(key, requester, value)));

		Arbitrary<Step> approveStep = Combinators
			.combine(Arbitraries.integers().between(0, 5), Arbitraries.of(STAFF))
			.as((ref, staff) -> (Step) new ApproveStep(ref, staff));

		Arbitrary<Step> cancelStep = Combinators
			.combine(Arbitraries.integers().between(0, 5), Arbitraries.of(STAFF))
			.as((ref, staff) -> (Step) new CancelStep(ref, staff));

		Arbitrary<Step> clockStep = Arbitraries.longs().between(0, 72).map(h -> (Step) new AdvanceClockStep(h));

		return Arbitraries.oneOf(requestStep, approveStep, cancelStep, clockStep);
	}

	/**
	 * A mix of every hard-bound edge (exactly at the bound, and one unit past it on the violating
	 * side) plus the key's default and a wide random spread, so both accepted and refused requests
	 * are generated for every key.
	 */
	private Arbitrary<BigDecimal> valuesFor(LimitKey key) {
		List<BigDecimal> edges = new ArrayList<>();
		edges.add(key.defaultValue());
		for (LimitKey.Bound bound : key.bounds()) {
			BigDecimal boundary = bound.value();
			edges.add(boundary);
			switch (bound.kind()) {
				case MAX_INCLUSIVE -> edges.add(boundary.add(BigDecimal.ONE));
				case MIN_INCLUSIVE -> edges.add(boundary.subtract(BigDecimal.ONE));
				case MIN_EXCLUSIVE -> edges.add(boundary.subtract(BigDecimal.ONE));
			}
		}

		long randomUpper = key.defaultValue().abs().add(BigDecimal.valueOf(100)).multiply(BigDecimal.valueOf(3))
			.longValueExact();
		Arbitrary<BigDecimal> randomSpread = Arbitraries.longs().between(-1000, randomUpper).map(BigDecimal::valueOf);

		return Arbitraries.oneOf(Arbitraries.of(edges), randomSpread);
	}

}

package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.ErrorResponseException;

import io.github.ciaran11221.compliance.rules.LimitKey;
import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;

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
 * service against a real Testcontainers Postgres, so a future change that weakens or bypasses that
 * check would show up here even though the assertion below never calls firstViolatedBound itself:
 * violatesHardBound(), below, re-derives the pass/fail of each LimitKey.Bound from its Kind
 * (MAX_INCLUSIVE / MIN_INCLUSIVE / MIN_EXCLUSIVE) independently.
 *
 * A single @Test loops 1,000 generated step sequences (1 to 6 steps each) against ONE Spring
 * context / Testcontainers Postgres for the whole run: one flyway.clean() + migrate() cycle was
 * timed with a one-off System.nanoTime() measurement at 459ms against this same Testcontainers
 * Postgres, so resetting between each of the 1,000 cases would add roughly 8 minutes to this one
 * test alone -- against the run's own 1,000-case loop finishing in under 30 seconds once the Spring
 * context and container are up. A per-case reset is also not needed for what this property checks:
 * "today's active value respects its bound" does not depend on starting from a pristine database,
 * only on every write along the way having gone through the bound check. State (and the clock) is
 * therefore allowed to accumulate across the 1,000 cases; each case's assertion is still a check of
 * the live system's current state. The database is reset once before the @Test (fresh seed data,
 * clock at START) and once more after it (@AfterEach), so nothing this test activates or tightens
 * leaks into another test class sharing this Spring context, matching the pattern in
 * quarantine.QuarantineExpiryJobTest and limits.LimitChangeConcurrentApprovalTest.
 *
 * The generator's first cases are deterministic: for every LimitKey, a request at each hard-bound
 * edge (refused) and one plain, valid TIGHTEN approved by a different-team staff member
 * (accepted, and -- since a TIGHTEN never needs more than 1 approval and is never treated as a
 * large loosening -- activated immediately, with no cooling-off wait). One deterministic LOOSEN
 * case for a non-priceable key (which RequiredApprovalsCalculator always treats as a large
 * loosening) exercises the 3-approval / COMPLIANCE / cooling-off path explicitly, advancing the
 * clock 25 hours so its activation becomes visible. The remaining cases are random step sequences
 * (seeded java.util.SplittableRandom, overridable with -Dm9.seed=N) drawing from a value
 * distribution weighted towards each key's bound edges and its default, so both accepted and
 * refused requests keep appearing throughout the run, not just in the deterministic prefix.
 *
 * Not covered: two threads racing on the same request (see LimitChangeConcurrentApprovalTest for
 * that), and any key whose bounds change in a future edit without this test being re-run.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import({ TestcontainersConfig.class, LimitBoundsPropertyTest.ClockOverride.class })
@ActiveProfiles("test")
class LimitBoundsPropertyTest {

	private static final long SEED = Long.getLong("m9.seed", 300719L);

	private static final int CASE_COUNT = 1000;

	private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

	private static final List<String> STAFF = List.of("anne", "brian", "sup-1", "sup-2", "sup-3", "comp-1", "exec-1");

	@Autowired
	private Flyway flyway;

	@Autowired
	private LimitChangeService service;

	@Autowired
	private MutableClock clock;

	@BeforeEach
	void resetDatabase() {
		flyway.clean();
		flyway.migrate();
		clock.set(START);
	}

	@AfterEach
	void resetDatabaseAfter() {
		flyway.clean();
		flyway.migrate();
		clock.set(START);
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

	@Test
	void activeValueNeverPastItsHardBound() {
		List<List<Step>> cases = generateCases();
		assertThat(cases).as("seed %d: generator must produce exactly %d cases", SEED, CASE_COUNT)
			.hasSize(CASE_COUNT);

		int refusedPastBoundCount = 0;
		int activatedCount = 0;

		for (int i = 0; i < cases.size(); i++) {
			List<Step> steps = cases.get(i);
			String label = "seed=" + SEED + " case=" + i + " steps=" + steps;

			Map<String, BigDecimal> before = service.activeLimits();
			List<Long> requestIds = new ArrayList<>();

			for (Step step : steps) {
				try {
					apply(step, requestIds);
				}
				catch (ErrorResponseException refusal) {
					if (step instanceof RequestStep r && refusal.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()
							&& violatesHardBound(r.key(), r.newValue())) {
						refusedPastBoundCount++;
					}
					// Any other refusal (already cancelled, stale, self-approval, already approved,
					// out of office, rule-11 conflict, "nothing to change", not found) is exactly what
					// the business rules are meant to do; only the counters and invariant below are
					// under test here.
				}
			}

			Map<String, BigDecimal> after = service.activeLimits();
			if (!after.equals(before)) {
				activatedCount++;
			}

			for (LimitKey key : LimitKey.values()) {
				BigDecimal value = after.get(key.name());
				assertThat(violatesHardBound(key, value)).as("%s's active value %s must respect its hard bounds (%s)",
						key, value, label).isFalse();
			}
		}

		System.out.println("M9 part b counters: refusedPastBoundCount=" + refusedPastBoundCount + " activatedCount="
				+ activatedCount + " of " + CASE_COUNT + " cases (seed=" + SEED + ")");

		assertThat(refusedPastBoundCount)
			.as("seed %d: at least one request past a bound must be refused with 422 (saw %d over %d cases)", SEED,
					refusedPastBoundCount, CASE_COUNT)
			.isGreaterThan(0);
		assertThat(activatedCount)
			.as("seed %d: at least one case must actually change an active value (saw %d over %d cases)", SEED,
					activatedCount, CASE_COUNT)
			.isGreaterThan(0);
	}

	/**
	 * Re-derives whether value crosses one of key's hard bounds directly from each Bound's Kind,
	 * without calling LimitKey.firstViolatedBound -- the very method requestChange() itself uses to
	 * decide the same question, which this test must not simply re-run.
	 */
	private boolean violatesHardBound(LimitKey key, BigDecimal value) {
		for (LimitKey.Bound bound : key.bounds()) {
			boolean violated = switch (bound.kind()) {
				case MAX_INCLUSIVE -> value.compareTo(bound.value()) > 0;
				case MIN_INCLUSIVE -> value.compareTo(bound.value()) < 0;
				case MIN_EXCLUSIVE -> value.compareTo(bound.value()) <= 0;
			};
			if (violated) {
				return true;
			}
		}
		return false;
	}

	// Plain instanceof, not a switch pattern: the project compiles at --release 17, where record
	// deconstruction patterns in switch are not available (they were finalized only in JDK 21).
	private void apply(Step step, List<Long> requestIds) {
		if (step instanceof RequestStep r) {
			LimitChangeRequestBody body = new LimitChangeRequestBody(r.key().name(), r.newValue(), "M9 property fuzz");
			LimitChangeView view = service.requestChange(r.requesterId(), body);
			requestIds.add(view.id());
		}
		else if (step instanceof ApproveStep a) {
			Long id = pick(requestIds, a.requestRef());
			if (id != null) {
				service.approve(id, a.approverId(), rolesOf(a.approverId()));
			}
		}
		else if (step instanceof CancelStep c) {
			Long id = pick(requestIds, c.requestRef());
			if (id != null) {
				service.cancel(id, c.callerId(), rolesOf(c.callerId()));
			}
		}
		else if (step instanceof AdvanceClockStep t) {
			clock.set(clock.instant().plus(t.hours(), ChronoUnit.HOURS));
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

	private List<List<Step>> generateCases() {
		List<List<Step>> cases = new ArrayList<>();
		addEdgeCases(cases);

		SplittableRandom random = new SplittableRandom(SEED);
		while (cases.size() < CASE_COUNT) {
			cases.add(randomSteps(random));
		}
		return cases;
	}

	/**
	 * For every LimitKey: a request at each hard-bound edge (each refused with 422) and a plain
	 * TIGHTEN request approved by a different-team staff member (accepted and activated
	 * immediately, since a TIGHTEN is never treated as a large loosening). anne (desk-a) requests,
	 * sup-2 (desk-b) approves, throughout, so the different-team check always passes. One
	 * deterministic LOOSEN of a non-priceable key (always "large" per RequiredApprovalsCalculator)
	 * is approved by three different-team staff including comp-1 (COMPLIANCE), then the clock is
	 * advanced 25 hours so its cooling-off delay elapses and the activation becomes visible.
	 */
	private void addEdgeCases(List<List<Step>> cases) {
		for (LimitKey key : LimitKey.values()) {
			for (BigDecimal edge : boundEdgeValues(key)) {
				cases.add(List.of(new RequestStep(key, "anne", edge)));
			}
			BigDecimal tightenValue = tightenValueFor(key);
			cases.add(List.of(new RequestStep(key, "anne", tightenValue), new ApproveStep(0, "sup-2")));
		}

		cases.add(List.of(new RequestStep(LimitKey.COOLING_OFF_HOURS, "anne", new BigDecimal("12")),
				new ApproveStep(0, "sup-2"), new ApproveStep(0, "comp-1"), new ApproveStep(0, "exec-1"),
				new AdvanceClockStep(25)));
	}

	/** Every bound value itself, plus one unit past it on the violating side. */
	private List<BigDecimal> boundEdgeValues(LimitKey key) {
		List<BigDecimal> edges = new ArrayList<>();
		for (LimitKey.Bound bound : key.bounds()) {
			edges.add(bound.value());
			switch (bound.kind()) {
				case MAX_INCLUSIVE -> edges.add(bound.value().add(BigDecimal.ONE));
				case MIN_INCLUSIVE -> edges.add(bound.value().subtract(BigDecimal.ONE));
				case MIN_EXCLUSIVE -> edges.add(bound.value().subtract(BigDecimal.ONE));
			}
		}
		return edges;
	}

	/** One unit in the direction opposite to key's looser direction, away from its default. */
	private BigDecimal tightenValueFor(LimitKey key) {
		BigDecimal delta = key.looserDirection() == LimitKey.LooserDirection.HIGHER ? BigDecimal.ONE.negate()
				: BigDecimal.ONE;
		return key.defaultValue().add(delta);
	}

	private List<Step> randomSteps(SplittableRandom random) {
		int size = random.nextInt(1, 7);
		List<Step> steps = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			steps.add(randomStep(random));
		}
		return steps;
	}

	private Step randomStep(SplittableRandom random) {
		return switch (random.nextInt(4)) {
			case 0 -> {
				LimitKey key = LimitKey.values()[random.nextInt(LimitKey.values().length)];
				yield new RequestStep(key, randomStaff(random), randomValueFor(random, key));
			}
			case 1 -> new ApproveStep(random.nextInt(0, 6), randomStaff(random));
			case 2 -> new CancelStep(random.nextInt(0, 6), randomStaff(random));
			default -> new AdvanceClockStep(random.nextLong(0, 73));
		};
	}

	private String randomStaff(SplittableRandom random) {
		return STAFF.get(random.nextInt(STAFF.size()));
	}

	/**
	 * A mix of every hard-bound edge (exactly at the bound, and one unit past it on the violating
	 * side) plus the key's default and a wide random spread, so both accepted and refused requests
	 * keep being generated for every key.
	 */
	private BigDecimal randomValueFor(SplittableRandom random, LimitKey key) {
		List<BigDecimal> edges = new ArrayList<>();
		edges.add(key.defaultValue());
		edges.addAll(boundEdgeValues(key));

		if (random.nextInt(3) != 0) {
			long randomUpper = key.defaultValue().abs().add(BigDecimal.valueOf(100)).multiply(BigDecimal.valueOf(3))
				.longValueExact();
			return BigDecimal.valueOf(random.nextLong(-1000, randomUpper + 1));
		}
		return edges.get(random.nextInt(edges.size()));
	}

}

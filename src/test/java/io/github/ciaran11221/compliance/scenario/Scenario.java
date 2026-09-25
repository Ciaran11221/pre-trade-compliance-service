package io.github.ciaran11221.compliance.scenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One scenario from the corpus at src/test/resources/scenarios/*.yaml: a fixture (fund,
 * securities, holdings, restricted list, pending orders), an order, and the outcome that order
 * is expected to produce. Every nested shape lives here rather than in its own file, so a reader
 * can see the whole shape of a scenario by opening this one file, the same reason
 * RouteAccessCsv.Row sits inside RouteAccessCsv rather than beside it.
 */
public record Scenario(String id, String title, String kind, List<String> covers, Given given, When when, Then then,
		List<Step> steps) {

	// Normalizes the one field a "rule"/"quarantine" scenario file never sets: those files predate
	// steps and have no "steps:" key at all, which Jackson leaves as null rather than an empty
	// list. Every other component keeps whatever Jackson gives it, unchanged.
	public Scenario {
		steps = steps == null ? List.of() : steps;
	}

	public record Given(Map<String, Object> settings, Fund fund, List<Security> securities, List<Holding> holdings,
			List<String> restricted, List<Order> pendingOrders, List<OutOfOffice> outOfOffice) {

		// Same reasoning as Scenario.steps: "rule"/"quarantine" files never set out-of-office data.
		public Given {
			outOfOffice = outOfOffice == null ? List.of() : outOfOffice;
		}

	}

	/** A staff member to mark out of office before a limit-change scenario's steps run. */
	public record OutOfOffice(String staffId, String from, String until) {
	}

	/**
	 * One step of a "kind: limit-change" scenario, run through the HTTP API by
	 * LimitChangeScenarioTest: who is acting, what they do, the fields that action needs, and what
	 * is expected to come back. actor/roles are required for "request", "approve" and "cancel";
	 * "advance-clock" needs neither. ref (in fields) names the request a step acts on or creates,
	 * scoped to this one scenario, so a scenario with more than one request (see the stale and
	 * hides-breach corpus) can say which one each step means.
	 */
	public record Step(String actor, List<String> roles, String action, Map<String, Object> fields, Expect expect) {
	}

	/**
	 * Whichever of these a step cares about; null means "not checked" for that field. limitValue is
	 * for a "get-limits" step only: the expected active value (as a plain decimal string) of the
	 * setting named by that step's fields.key, read back from GET /api/limits.
	 */
	public record Expect(Integer httpStatus, String resultStatus, Integer requiredApprovals, Boolean hidesBreach,
			String limitValue) {
	}

	public record Fund(String code, BigDecimal totalAssets, BigDecimal cash, boolean diversified) {
	}

	// issuer is nullable: scenarios written before issuer aggregation mattered (S001-S006) omit
	// it and default to the ticker as its own issuer. See RuleScenarioTest.buildContext.
	public record Security(String ticker, String issuer, BigDecimal price, long votingSharesOutstanding,
			long avgDailyVolume) {
	}

	public record Holding(String ticker, long quantity) {
	}

	public record Order(String side, String ticker, long quantity) {
	}

	public record When(Order order) {
	}

	public record Then(String outcome, Map<String, String> rules, String note) {
	}

}

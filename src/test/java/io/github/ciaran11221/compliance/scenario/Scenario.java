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
public record Scenario(String id, String title, String kind, List<String> covers, Given given, When when, Then then) {

	public record Given(Map<String, Object> settings, Fund fund, List<Security> securities, List<Holding> holdings,
			List<String> restricted, List<Order> pendingOrders) {
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

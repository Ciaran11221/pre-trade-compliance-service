package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.github.ciaran11221.compliance.scenario.Scenario;
import io.github.ciaran11221.compliance.scenario.ScenarioLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every "kind: rule" scenario in the corpus against the real rules and the real engine.
 * ComplianceRule implementations are plain pure classes, so this needs no Spring context, no
 * database and no mocks. ScenarioRunnerCoverageTest is what proves the "rule" kind actually has a
 * runner registered at all.
 */
class RuleScenarioTest {

	private static final List<ComplianceRule> RULES = List
		.of(new RestrictedListRule(), new CashRule(), new OrderSizeRule(), new DiversificationRule(),
				new HoldingRule());

	@TestFactory
	List<DynamicTest> ruleScenarios() {
		List<DynamicTest> tests = new ArrayList<>();
		for (Scenario scenario : ScenarioLoader.loadAllValidated()) {
			if (!"rule".equals(scenario.kind())) {
				continue;
			}
			tests.add(DynamicTest.dynamicTest(scenario.id() + " " + scenario.title(), () -> run(scenario)));
		}
		return tests;
	}

	private void run(Scenario scenario) {
		OrderContext context = buildContext(scenario);
		ComplianceEngine.EngineResult result = new ComplianceEngine(RULES).evaluate(context);

		assertThat(result.outcome().name()).as("overall outcome for %s", scenario.id())
			.isEqualTo(scenario.then().outcome());

		Map<String, RuleResult> byName = new LinkedHashMap<>();
		result.results().forEach(r -> byName.put(r.ruleName(), r));

		scenario.then().rules().forEach((ruleName, expectedOutcome) -> {
			RuleResult actual = byName.get(ruleName);
			assertThat(actual).as("no result for rule \"%s\" in %s", ruleName, scenario.id()).isNotNull();
			assertThat(actual.outcome().name()).as("outcome of rule \"%s\" in %s", ruleName, scenario.id())
				.isEqualTo(expectedOutcome);
		});
	}

	static OrderContext buildContext(Scenario scenario) {
		Scenario.Given given = scenario.given();

		Map<String, OrderContext.SecurityInfo> securities = new LinkedHashMap<>();
		for (Scenario.Security security : given.securities()) {
			String issuer = security.issuer() != null ? security.issuer() : security.ticker();
			securities.put(security.ticker(), new OrderContext.SecurityInfo(issuer, security.price(),
					security.votingSharesOutstanding(), security.avgDailyVolume()));
		}

		Map<String, Long> holdings = new LinkedHashMap<>();
		for (Scenario.Holding holding : given.holdings()) {
			holdings.put(holding.ticker(), holding.quantity());
		}

		Set<String> restricted = new LinkedHashSet<>(given.restricted());

		List<OrderContext.PendingOrder> pendingOrders = new ArrayList<>();
		for (Scenario.Order pending : given.pendingOrders()) {
			BigDecimal price = securities.get(pending.ticker()).price();
			pendingOrders.add(new OrderContext.PendingOrder(OrderContext.Side.valueOf(pending.side()),
					pending.ticker(), pending.quantity(), price));
		}

		OrderContext.Fund fund = new OrderContext.Fund(given.fund().totalAssets(), given.fund().cash(),
				given.fund().diversified());

		Scenario.Order orderYaml = scenario.when().order();
		BigDecimal orderPrice = securities.get(orderYaml.ticker()).price();
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.valueOf(orderYaml.side()),
				orderYaml.ticker(), orderYaml.quantity(), orderPrice);

		Map<LimitKey, BigDecimal> limitValues = new EnumMap<>(LimitKey.class);
		for (LimitKey key : LimitKey.values()) {
			limitValues.put(key, key.defaultValue());
		}
		if (given.settings() != null) {
			given.settings()
				.forEach((key, value) -> limitValues.put(LimitKey.valueOf(key), new BigDecimal(value.toString())));
		}

		return new OrderContext(fund, order, holdings, securities, restricted, pendingOrders, new Limits(limitValues));
	}

}

package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestrictedListRuleTest {

	private final RestrictedListRule rule = new RestrictedListRule();

	@Test
	void buyOfARestrictedSecurityBlocks() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.BUY, "ZPHR", Set.of("ZPHR")));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	@Test
	void sellOfARestrictedSecurityAlsoBlocks() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.SELL, "ZPHR", Set.of("ZPHR")));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	@Test
	void buyOfAnUnrestrictedSecurityPasses() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.BUY, "KSTL", Set.of("ZPHR")));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

	private OrderContext context(OrderContext.Side side, String ticker, Set<String> restricted) {
		Map<String, OrderContext.SecurityInfo> securities = Map.of(ticker,
				new OrderContext.SecurityInfo(ticker, new BigDecimal("100"), 50_000_000L, 2_000_000L));
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		OrderContext.Order order = new OrderContext.Order(side, ticker, 10_000L, new BigDecimal("100"));
		return new OrderContext(fund, order, Map.of(), securities, restricted, List.of(), Limits.defaults());
	}

}

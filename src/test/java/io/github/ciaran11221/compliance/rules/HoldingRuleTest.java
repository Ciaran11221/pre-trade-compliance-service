package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingRuleTest {

	private final HoldingRule rule = new HoldingRule();

	private static final Map<String, OrderContext.SecurityInfo> SECURITIES = Map.of("KSTL",
			new OrderContext.SecurityInfo("KSTL", new BigDecimal("100"), 50_000_000L, 2_000_000L));

	@Test
	void sellWithinTheHoldingPasses() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.SELL, 100_000L, holdings(200_000L), List.of()));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

	@Test
	void sellExactlyEqualToTheHoldingPasses() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.SELL, 200_000L, holdings(200_000L), List.of()));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

	@Test
	void sellOneShareAboveTheHoldingBlocks() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.SELL, 200_001L, holdings(200_000L), List.of()));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	@Test
	void aPendingSellReducesWhatIsAvailableSoASecondSellThatWouldOtherwiseFitBlocks() {
		// Holding of 200,000: a first pending sell of 150,000 leaves 50,000 available. A second sell
		// of 60,000 fits alone (60,000 <= 200,000) but not once the first pending sell is counted.
		List<OrderContext.PendingOrder> pending = List
			.of(new OrderContext.PendingOrder(OrderContext.Side.SELL, "KSTL", 150_000L, new BigDecimal("100")));

		RuleResult result = rule.evaluate(context(OrderContext.Side.SELL, 60_000L, holdings(200_000L), pending));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	@Test
	void aBuyIsNotApplicable() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.BUY, 100_000L, holdings(200_000L), List.of()));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.NOT_APPLICABLE);
	}

	@Test
	void aSecurityNotHeldAtAllBlocksAnySell() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.SELL, 1L, Map.of(), List.of()));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	private OrderContext context(OrderContext.Side side, long quantity, Map<String, Long> holdings,
			List<OrderContext.PendingOrder> pendingOrders) {
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		OrderContext.Order order = new OrderContext.Order(side, "KSTL", quantity, new BigDecimal("100"));
		return new OrderContext(fund, order, holdings, SECURITIES, Set.of(), pendingOrders, Limits.defaults());
	}

	private Map<String, Long> holdings(long quantity) {
		return Map.of("KSTL", quantity);
	}

}

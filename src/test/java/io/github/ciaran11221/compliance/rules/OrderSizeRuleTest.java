package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSizeRuleTest {

	private final OrderSizeRule rule = new OrderSizeRule();

	// Average daily volume is 2,000,000 shares; the default ORDER_SIZE_ADV_PCT is 10%, so the
	// threshold is exactly 200,000 shares.
	private static final Map<String, OrderContext.SecurityInfo> SECURITIES = Map.of("KSTL",
			new OrderContext.SecurityInfo("KSTL", new BigDecimal("100"), 50_000_000L, 2_000_000L));

	@Test
	void quantityExactlyAtTenPercentOfAverageDailyVolumePasses() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.BUY, 200_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

	@Test
	void oneShareOverTenPercentOfAverageDailyVolumeGoesToReview() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.BUY, 200_001L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.REVIEW);
	}

	@Test
	void aSellAboveTheThresholdAlsoGoesToReview() {
		RuleResult result = rule.evaluate(context(OrderContext.Side.SELL, 250_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.REVIEW);
	}

	private OrderContext context(OrderContext.Side side, long quantity) {
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		OrderContext.Order order = new OrderContext.Order(side, "KSTL", quantity, new BigDecimal("100"));
		return new OrderContext(fund, order, Map.of(), SECURITIES, Set.of(), List.of(), Limits.defaults());
	}

}

package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CashRuleTest {

	private final CashRule rule = new CashRule();

	private static final Map<String, OrderContext.SecurityInfo> SECURITIES = Map.of("KSTL",
			new OrderContext.SecurityInfo("KSTL", new BigDecimal("100"), 50_000_000L, 2_000_000L), "NRTH",
			new OrderContext.SecurityInfo("NRTH", new BigDecimal("150"), 40_000_000L, 1_500_000L));

	@Test
	void sellIsNotApplicable() {
		RuleResult result = rule.evaluate(context(new BigDecimal("10000000"), List.of(), OrderContext.Side.SELL, 50_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.NOT_APPLICABLE);
	}

	@Test
	void buyWithinCashPasses() {
		RuleResult result = rule.evaluate(context(new BigDecimal("10000000"), List.of(), OrderContext.Side.BUY, 50_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

	@Test
	void buyExceedingCashBlocks() {
		RuleResult result = rule.evaluate(context(new BigDecimal("1000000"), List.of(), OrderContext.Side.BUY, 50_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	@Test
	void orderValueExactlyEqualToAvailableCashPasses() {
		// 50,000 KSTL sh x $100 = $5,000,000 exactly, and cash is exactly $5,000,000: not blocked,
		// since only strictly exceeding the available cash blocks.
		RuleResult result = rule.evaluate(context(new BigDecimal("5000000"), List.of(), OrderContext.Side.BUY, 50_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

	@Test
	void aPendingBuyReducesAvailableCashAndCanBlock() {
		OrderContext.PendingOrder pendingBuy = new OrderContext.PendingOrder(OrderContext.Side.BUY, "NRTH", 40_000L,
				new BigDecimal("150"));
		// Cash is $10,000,000; the pending buy alone is $6,000,000, leaving $4,000,000 available.
		// The new order is $5,000,000, more than what is left.
		RuleResult result = rule
			.evaluate(context(new BigDecimal("10000000"), List.of(pendingBuy), OrderContext.Side.BUY, 50_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	@Test
	void aPendingSellIsIgnoredAndDoesNotFreeUpCash() {
		OrderContext.PendingOrder pendingSell = new OrderContext.PendingOrder(OrderContext.Side.SELL, "NRTH", 40_000L,
				new BigDecimal("150"));
		// Cash is only $1,000,000, far short of the $5,000,000 order, even though a pending sell of
		// NRTH exists: it gives no credit until it fills.
		RuleResult result = rule
			.evaluate(context(new BigDecimal("1000000"), List.of(pendingSell), OrderContext.Side.BUY, 50_000L));

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
	}

	private OrderContext context(BigDecimal cash, List<OrderContext.PendingOrder> pendingOrders, OrderContext.Side side,
			long quantity) {
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), cash, true);
		OrderContext.Order order = new OrderContext.Order(side, "KSTL", quantity, new BigDecimal("100"));
		return new OrderContext(fund, order, Map.of(), SECURITIES, Set.of(), pendingOrders, Limits.defaults());
	}

}

package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiversificationRuleTest {

	private final DiversificationRule rule = new DiversificationRule();

	@Test
	void nonDiversifiedFundIsNotApplicable() {
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), false);
		Map<String, OrderContext.SecurityInfo> securities = Map.of("KSTL",
				new OrderContext.SecurityInfo("KSTL", new BigDecimal("100"), 50_000_000L, 2_000_000L));
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.BUY, "KSTL", 100_000L, new BigDecimal("100"));
		OrderContext context = new OrderContext(fund, order, Map.of(), securities, Set.of(), List.of(), Limits.defaults());

		assertThat(rule.evaluate(context).outcome()).isEqualTo(RuleOutcome.NOT_APPLICABLE);
	}

	@Test
	void sellIsNotApplicable() {
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		Map<String, OrderContext.SecurityInfo> securities = Map.of("KSTL",
				new OrderContext.SecurityInfo("KSTL", new BigDecimal("100"), 50_000_000L, 2_000_000L));
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.SELL, "KSTL", 100_000L, new BigDecimal("100"));
		OrderContext context = new OrderContext(fund, order, Map.of("KSTL", 600_000L), securities, Set.of(), List.of(),
				Limits.defaults());

		assertThat(rule.evaluate(context).outcome()).isEqualTo(RuleOutcome.NOT_APPLICABLE);
	}

	@Test
	void anIssuerAtExactlyFivePercentIsNotOverAndABucketAtExactlyTwentyFivePercentPasses() {
		// AXIOM alone is already over 5%, at exactly 25% of assets: $250,000,000 of $1,000,000,000.
		// The order buys FENN up to exactly 5% of assets, $50,000,000, which is not over 5% since
		// "over" is strictly greater than the limit. So the over-5% bucket stays at exactly 25% post
		// trade, not strictly greater than the 25% limit, so this passes.
		Map<String, OrderContext.SecurityInfo> securities = Map.of("AXIOM",
				new OrderContext.SecurityInfo("Axiom Networks", new BigDecimal("250"), 200_000_000L, 5_000_000L), "FENN",
				new OrderContext.SecurityInfo("Fennrock Holdings", new BigDecimal("100"), 10_000_000L, 10_000_000L));
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.BUY, "FENN", 500_000L, new BigDecimal("100"));
		OrderContext context = new OrderContext(fund, order, Map.of("AXIOM", 1_000_000L), securities, Set.of(), List.of(),
				Limits.defaults());

		RuleResult result = rule.evaluate(context);

		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
		assertThat(result.measuredValue()).isEqualByComparingTo(new BigDecimal("25.0000"));
	}

	@Test
	void anIssuerOverOnVotingAloneCanBlockEvenWhenItsValueAloneWouldNot() {
		// OVRB is already over 5% on value alone, at 24.5% of assets. VOTC's value is only 1% of
		// assets (would not be "over" on value), but the order takes it to more than 10% of VOTC's
		// own voting shares outstanding, so VOTC counts as over too, taking the bucket to 25.5%,
		// over the 25% limit and up from 24.5% pre-trade, so this blocks.
		Map<String, OrderContext.SecurityInfo> securities = Map.of("OVRB",
				new OrderContext.SecurityInfo("Overbrook Capital", new BigDecimal("100"), 10_000_000_000L, 5_000_000L),
				"VOTC", new OrderContext.SecurityInfo("Votec Systems", new BigDecimal("100"), 500_000L, 5_000_000L));
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.BUY, "VOTC", 100_000L, new BigDecimal("100"));
		OrderContext context = new OrderContext(fund, order, Map.of("OVRB", 2_450_000L), securities, Set.of(), List.of(),
				Limits.defaults());

		RuleResult result = rule.evaluate(context);

		assertThat(result.outcome()).isEqualTo(RuleOutcome.BLOCK);
		assertThat(result.measuredValue()).isEqualByComparingTo(new BigDecimal("25.5000"));
	}

	@Test
	void aPendingSellDoesNotReduceTheHeldPosition() {
		// PSEL is held at 505,000 sh x $100 = $50,500,000, already over 5%. A pending sell of 10,000
		// PSEL shares gives no credit until it fills, so it must not reduce PSEL's counted position.
		// The order itself buys an unrelated, far smaller security that stays well under 5%.
		Map<String, OrderContext.SecurityInfo> securities = Map.of("PSEL",
				new OrderContext.SecurityInfo("Pseldon Corp", new BigDecimal("100"), 100_000_000L, 5_000_000L), "OTHR",
				new OrderContext.SecurityInfo("Othergate Ltd", new BigDecimal("10"), 100_000_000L, 5_000_000L));
		OrderContext.PendingOrder pendingSell = new OrderContext.PendingOrder(OrderContext.Side.SELL, "PSEL", 10_000L,
				new BigDecimal("100"));
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.BUY, "OTHR", 100L, new BigDecimal("10"));
		OrderContext context = new OrderContext(fund, order, Map.of("PSEL", 505_000L), securities, Set.of(),
				List.of(pendingSell), Limits.defaults());

		RuleResult result = rule.evaluate(context);

		assertThat(result.measuredValue()).isEqualByComparingTo(new BigDecimal("5.0500"));
		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

	@Test
	void aPendingBuyIsIncludedInThePosition() {
		// PBUY has no holding. A pending buy of 300,000 shares, plus this order's own 300,000 shares,
		// takes the post-trade position to 600,000 sh x $100 = $60,000,000, over 5%, which neither
		// the pending buy nor the order alone would be.
		Map<String, OrderContext.SecurityInfo> securities = Map.of("PBUY",
				new OrderContext.SecurityInfo("Pebrook Inc", new BigDecimal("100"), 100_000_000L, 5_000_000L));
		OrderContext.PendingOrder pendingBuy = new OrderContext.PendingOrder(OrderContext.Side.BUY, "PBUY", 300_000L,
				new BigDecimal("100"));
		OrderContext.Fund fund = new OrderContext.Fund(new BigDecimal("1000000000"), new BigDecimal("100000000"), true);
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.BUY, "PBUY", 300_000L, new BigDecimal("100"));
		OrderContext context = new OrderContext(fund, order, Map.of(), securities, Set.of(), List.of(pendingBuy),
				Limits.defaults());

		RuleResult result = rule.evaluate(context);

		assertThat(result.measuredValue()).isEqualByComparingTo(new BigDecimal("6.0000"));
		assertThat(result.outcome()).isEqualTo(RuleOutcome.PASS);
	}

}

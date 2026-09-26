package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for the 75-5-10 diversification rule's bucket boundary (M9, issue #30 part a):
 * a buy that pushes the sum of "over" issuer positions past OVER_LIMIT_BUCKET_PCT of fund assets
 * is BLOCK from DiversificationRule, and a buy that keeps that sum at or under the bucket limit is
 * not BLOCKed.
 *
 * The expected outcome is computed here by an oracle written independently from
 * DiversificationRule / IssuerOverLimitCalculator, straight from the definition in
 * DiversificationRule's javadoc, so the test cannot pass merely because it re-runs the same code.
 *
 * Every security in a generated case is priced at exactly $1, so a position's dollar value equals
 * its share quantity. Fund assets are always a multiple of 100 and voting-shares-outstanding a
 * multiple of 100, which keeps every percentage threshold (5%, 10%, 25%) an exact integer number of
 * shares, so edge cases (a position at exactly the limit, one share over it) are exact rather than
 * rounded.
 *
 * "Over" for a position is (value > ISSUER_LIMIT_PCT% of assets) OR (quantity > VOTING_LIMIT_PCT%
 * of the security's voting shares outstanding); since both are plain ">" tests against the same
 * quantity, that OR is equivalent to quantity > min(issuerThreshold, voteThreshold), which is what
 * the oracle below uses as the "effective threshold" for the traded issuer. A pre-existing "other"
 * issuer, when included, is always constructed strictly over its issuer threshold, so it always
 * counts fully towards the bucket sum, standing in for one or more other over-5% issuers already in
 * the fund.
 */
class DiversificationBucketPropertyTest {

	private final DiversificationRule rule = new DiversificationRule();

	private static final BigDecimal ONE = BigDecimal.ONE;

	private static final long HUGE_VOTING_SHARES = 1_000_000_000_000L;

	/** One generated scenario: a fund, an "other" over-5% issuer (maybe), and a traded issuer. */
	record Case(long totalAssets, long votingShares, long tradePreValue, long buyQuantity, long otherValue,
			boolean hasOther) {
	}

	@Property(tries = 1000)
	void buyPastTheBucketLimitBlocksAndAtOrUnderDoesNot(@ForAll("cases") Case c) {
		long issuerThreshold = c.totalAssets() / 20; // 5% of assets, exact since totalAssets % 100 == 0
		long voteThreshold = c.votingShares() / 10; // 10% of voting shares, exact since votingShares % 100 == 0
		long effectiveThreshold = Math.min(issuerThreshold, voteThreshold);
		long bucketThreshold = c.totalAssets() / 4; // 25% of assets, exact since totalAssets % 100 == 0

		long tradePostValue = c.tradePreValue() + c.buyQuantity();
		boolean tradeOverPre = c.tradePreValue() > effectiveThreshold;
		boolean tradeOverPost = tradePostValue > effectiveThreshold;
		long otherContribution = c.hasOther() ? c.otherValue() : 0L;

		long overSumPre = otherContribution + (tradeOverPre ? c.tradePreValue() : 0L);
		long overSumPost = otherContribution + (tradeOverPost ? tradePostValue : 0L);

		boolean expectedBlock = overSumPost > bucketThreshold && overSumPost > overSumPre;

		OrderContext context = buildContext(c, tradePostValue);
		RuleResult result = rule.evaluate(context);

		if (expectedBlock) {
			assertThat(result.outcome()).as(
					"expected BLOCK: post-trade over-sum %d > bucket threshold %d and grew from %d (case %s)",
					overSumPost, bucketThreshold, overSumPre, c).isEqualTo(RuleOutcome.BLOCK);
		}
		else {
			assertThat(result.outcome()).as(
					"expected not BLOCK: post-trade over-sum %d, bucket threshold %d, pre-trade over-sum %d (case %s)",
					overSumPost, bucketThreshold, overSumPre, c).isNotEqualTo(RuleOutcome.BLOCK);
		}
	}

	private OrderContext buildContext(Case c, long tradePostValueUnused) {
		Map<String, OrderContext.SecurityInfo> securities = new LinkedHashMap<>();
		securities.put("TRADE", new OrderContext.SecurityInfo("Trade Issuer", ONE, c.votingShares(), 1_000_000L));
		Map<String, Long> holdings = new LinkedHashMap<>();
		holdings.put("TRADE", c.tradePreValue());
		if (c.hasOther()) {
			securities.put("OTHER", new OrderContext.SecurityInfo("Other Issuer", ONE, HUGE_VOTING_SHARES, 1_000_000L));
			holdings.put("OTHER", c.otherValue());
		}

		OrderContext.Fund fund = new OrderContext.Fund(BigDecimal.valueOf(c.totalAssets()), BigDecimal.ZERO, true);
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.BUY, "TRADE", c.buyQuantity(), ONE);
		return new OrderContext(fund, order, holdings, securities, Set.of(), List.of(), Limits.defaults());
	}

	@Provide
	Arbitrary<Case> cases() {
		Arbitrary<Long> totalAssetsK = Arbitraries.longs().between(10, 20_000_000);
		Arbitrary<Long> votingSharesM = Arbitraries.longs().between(1, 20_000_000);

		return Combinators.combine(totalAssetsK, votingSharesM).as((k, m) -> new long[] { k * 100, m * 100 })
			.flatMap(bases -> {
				long totalAssets = bases[0];
				long votingShares = bases[1];
				long issuerThreshold = totalAssets / 20;
				long voteThreshold = votingShares / 10;
				long effectiveThreshold = Math.min(issuerThreshold, voteThreshold);
				long span = effectiveThreshold * 3 + 1000;

				Arbitrary<Long> preValueArb = Arbitraries.oneOf(Arbitraries.just(0L),
						Arbitraries.just(effectiveThreshold), Arbitraries.just(effectiveThreshold + 1),
						Arbitraries.longs().between(0, span));

				return preValueArb.flatMap(preValue -> {
					long toThreshold = effectiveThreshold - preValue;
					List<Long> edgeBuys = new ArrayList<>();
					edgeBuys.add(1L);
					if (toThreshold > 0) {
						edgeBuys.add(toThreshold);
						edgeBuys.add(toThreshold + 1);
					}
					Arbitrary<Long> buyArb = Arbitraries.oneOf(Arbitraries.of(edgeBuys),
							Arbitraries.longs().between(1, span + 1));

					Arbitrary<Long> otherExtraArb = Arbitraries.longs().between(0, issuerThreshold + span);
					Arbitrary<Boolean> hasOtherArb = Arbitraries.of(true, false);

					return Combinators.combine(buyArb, otherExtraArb, hasOtherArb)
						.as((buy, otherExtra, hasOther) -> new Case(totalAssets, votingShares, preValue, buy,
								issuerThreshold + 1 + otherExtra, hasOther));
				});
			});
	}

}

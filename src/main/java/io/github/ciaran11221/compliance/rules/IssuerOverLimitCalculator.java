package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The US 1940 Act 75-5-10 issuer-aggregation math: given a set of positions, sums the value of
 * every issuer that is "over" -- its positions summed by issuer exceed issuerLimitPct of total
 * assets, or any one of its securities alone exceeds votingLimitPct of that security's own voting
 * shares outstanding.
 *
 * Pulled out of DiversificationRule so the limit-change impact preview (limits package) can run
 * the exact same calculation against proposed limits, using current holdings, without the two
 * ever being able to disagree. DiversificationRule delegates its own overSum to this class
 * unchanged; see DiversificationRuleTest / the S001-S010 scenario corpus, which still pass without
 * modification.
 */
public final class IssuerOverLimitCalculator {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private IssuerOverLimitCalculator() {
	}

	/** The three fields of a security this calculation needs, independent of how it is stored. */
	public record SecurityRef(String issuer, BigDecimal price, long votingSharesOutstanding) {
	}

	/**
	 * @param positionsByTicker  quantity held of each ticker (zero or negative quantities are
	 *                           ignored)
	 * @param securities         looks up a ticker's issuer, price and voting shares outstanding
	 * @param totalAssets        the fund's total assets
	 * @param issuerLimitPct     the issuer-concentration percentage to test each issuer's summed
	 *                           value against
	 * @param votingLimitPct     the voting-control percentage to test each security against
	 * @return the summed value of every issuer whose aggregated position is over either limit
	 */
	public static BigDecimal overSum(Map<String, Long> positionsByTicker, Function<String, SecurityRef> securities,
			BigDecimal totalAssets, BigDecimal issuerLimitPct, BigDecimal votingLimitPct) {
		Map<String, BigDecimal> valueByIssuer = new LinkedHashMap<>();
		Map<String, Boolean> votingOverByIssuer = new LinkedHashMap<>();

		positionsByTicker.forEach((ticker, quantity) -> {
			if (quantity <= 0) {
				return;
			}
			SecurityRef info = securities.apply(ticker);
			BigDecimal value = info.price().multiply(BigDecimal.valueOf(quantity));
			valueByIssuer.merge(info.issuer(), value, BigDecimal::add);

			boolean votingOver = BigDecimal.valueOf(quantity)
				.multiply(HUNDRED)
				.compareTo(votingLimitPct.multiply(BigDecimal.valueOf(info.votingSharesOutstanding()))) > 0;
			if (votingOver) {
				votingOverByIssuer.merge(info.issuer(), true, Boolean::logicalOr);
			}
		});

		BigDecimal overSum = BigDecimal.ZERO;
		for (Map.Entry<String, BigDecimal> entry : valueByIssuer.entrySet()) {
			BigDecimal value = entry.getValue();
			boolean valueOver = value.multiply(HUNDRED).compareTo(issuerLimitPct.multiply(totalAssets)) > 0;
			boolean votingOver = votingOverByIssuer.getOrDefault(entry.getKey(), false);
			if (valueOver || votingOver) {
				overSum = overSum.add(value);
			}
		}
		return overSum;
	}

	/** Whether an over-5% total itself sits over the bucket limit: overSum > bucketPct% of assets. */
	public static boolean overBucket(BigDecimal overSum, BigDecimal bucketPct, BigDecimal totalAssets) {
		return overSum.multiply(HUNDRED).compareTo(bucketPct.multiply(totalAssets)) > 0;
	}

}

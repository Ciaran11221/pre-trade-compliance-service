package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * The US 1940 Act 75-5-10 test, as it applies pre-trade: no more than 25% of a diversified fund's
 * assets may sit in issuers each holding over 5% of the fund. Not applicable to a fund that is not
 * registered as diversified, or to a sell, which cannot grow an over-5% position.
 *
 * Aggregation is by ISSUER, not by security, because that is what the statute limits: two
 * securities of the same issuer are summed into one position. Simplification, stated here because
 * this dataset has no multi-class issuers to test it against: voting control is checked per
 * security against that security's own outstanding voting shares, not pooled across the issuer's
 * securities, so an issuer counts as over if its summed value exceeds ISSUER_LIMIT_PCT of total
 * assets OR any single one of its securities exceeds VOTING_LIMIT_PCT of that security's own
 * voting shares outstanding. "Over" is strictly greater than the limit; exactly at it is not over.
 *
 * Post-trade positions are holdings plus every pending BUY plus this order. A pending SELL gives
 * no credit until it actually fills, so it neither adds to nor subtracts from a position here.
 *
 * The statute is tested at the moment of acquisition, so a fund already pushed over 25% by price
 * moves is not forced to sell, and may still buy a position that does not itself add to the
 * over-limit group. The decision therefore blocks only when the buy both leaves the fund over the
 * 25% bucket AND grows that bucket's total beyond what it was before this order.
 */
@Component
public class DiversificationRule implements ComplianceRule {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	@Override
	public String name() {
		return "diversification";
	}

	@Override
	public RuleResult evaluate(OrderContext context) {
		if (!context.fund().diversified()) {
			return new RuleResult(name(), RuleOutcome.NOT_APPLICABLE,
					"fund is not registered as diversified, so the 75-5-10 rule does not apply.", null, null);
		}
		if (context.order().side() == OrderContext.Side.SELL) {
			return new RuleResult(name(), RuleOutcome.NOT_APPLICABLE,
					"a sell cannot grow an over-5% position, so the rule does not apply.", null, null);
		}

		BigDecimal totalAssets = context.fund().totalAssets();
		BigDecimal overSumPre = overSum(context, positions(context, false), totalAssets);
		BigDecimal overSumPost = overSum(context, positions(context, true), totalAssets);

		BigDecimal bucketPct = context.limits().get(LimitKey.OVER_LIMIT_BUCKET_PCT);
		boolean overBucket = overSumPost.multiply(HUNDRED).compareTo(bucketPct.multiply(totalAssets)) > 0;
		boolean grew = overSumPost.compareTo(overSumPre) > 0;
		BigDecimal measuredPct = overSumPost.multiply(HUNDRED).divide(totalAssets, 4, RoundingMode.HALF_UP);

		if (overBucket && grew) {
			String reason = String.format(
					"post-trade over-5%% issuer positions total $%,.2f (%s%% of fund assets), above the %s%% limit "
							+ "and up from $%,.2f pre-trade, so this buy grows the over-limit group.",
					overSumPost, measuredPct.stripTrailingZeros().toPlainString(),
					bucketPct.stripTrailingZeros().toPlainString(), overSumPre);
			return new RuleResult(name(), RuleOutcome.BLOCK, reason, measuredPct, bucketPct);
		}

		String reason = String.format(
				"post-trade over-5%% issuer positions total $%,.2f (%s%% of fund assets); either at or under the "
						+ "%s%% limit, or unchanged from the $%,.2f pre-trade total, so the buy passes.",
				overSumPost, measuredPct.stripTrailingZeros().toPlainString(),
				bucketPct.stripTrailingZeros().toPlainString(), overSumPre);
		return new RuleResult(name(), RuleOutcome.PASS, reason, measuredPct, bucketPct);
	}

	private Map<String, Long> positions(OrderContext context, boolean includeThisOrder) {
		Map<String, Long> positions = new LinkedHashMap<>();
		context.holdings().forEach((ticker, quantity) -> positions.merge(ticker, quantity, Long::sum));
		for (OrderContext.PendingOrder pending : context.pendingOrders()) {
			// Pending SELLs give no credit until filled, so only pending BUYs are added here.
			if (pending.side() == OrderContext.Side.BUY) {
				positions.merge(pending.security(), pending.quantity(), Long::sum);
			}
		}
		if (includeThisOrder) {
			positions.merge(context.order().security(), context.order().quantity(), Long::sum);
		}
		return positions;
	}

	private BigDecimal overSum(OrderContext context, Map<String, Long> positions, BigDecimal totalAssets) {
		BigDecimal votingLimitPct = context.limits().get(LimitKey.VOTING_LIMIT_PCT);
		Map<String, BigDecimal> valueByIssuer = new LinkedHashMap<>();
		Map<String, Boolean> votingOverByIssuer = new LinkedHashMap<>();

		positions.forEach((ticker, quantity) -> {
			if (quantity <= 0) {
				return;
			}
			OrderContext.SecurityInfo info = context.securityInfo(ticker);
			BigDecimal value = info.price().multiply(BigDecimal.valueOf(quantity));
			valueByIssuer.merge(info.issuer(), value, BigDecimal::add);

			boolean votingOver = BigDecimal.valueOf(quantity)
				.multiply(HUNDRED)
				.compareTo(votingLimitPct.multiply(BigDecimal.valueOf(info.votingSharesOutstanding()))) > 0;
			if (votingOver) {
				votingOverByIssuer.merge(info.issuer(), true, Boolean::logicalOr);
			}
		});

		BigDecimal issuerLimitPct = context.limits().get(LimitKey.ISSUER_LIMIT_PCT);
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

}

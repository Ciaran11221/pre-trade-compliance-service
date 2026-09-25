package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;

/**
 * Rule 3: a TIGHTEN needs 1 approval. A LOOSEN needs 2, or 3 if it is LARGE. LARGE means the
 * preview hides a breach, or its dollar impact cannot be computed at all, or its dollar impact is
 * positive and at or above a threshold -- the lower of a flat dollar figure and a percentage of the
 * total assets of the funds whose headroom the change actually increases. Raising the flat dollar
 * figure or the percentage is itself a change to a non-priceable key, so it is always LARGE: nobody
 * can quietly widen the definition of "large" through a single-approval loophole.
 */
public final class RequiredApprovalsCalculator {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private RequiredApprovalsCalculator() {
	}

	public static boolean isLarge(ImpactPreview preview, BigDecimal largeLooseningUsd,
			BigDecimal largeLooseningPctOfFunds) {
		if (preview.hidesBreach()) {
			return true;
		}
		BigDecimal usd = preview.usdNewlyAllowed();
		if (usd == null) {
			return true;
		}
		if (usd.compareTo(BigDecimal.ZERO) <= 0) {
			return false;
		}
		BigDecimal percentageThreshold = largeLooseningPctOfFunds.multiply(preview.totalAssetsOfFundsWithIncreasedHeadroom())
			.divide(HUNDRED, 4, java.math.RoundingMode.HALF_UP);
		BigDecimal threshold = largeLooseningUsd.min(percentageThreshold);
		return usd.compareTo(threshold) >= 0;
	}

	public static int requiredApprovals(LimitChangeDirection direction, ImpactPreview preview,
			BigDecimal largeLooseningUsd, BigDecimal largeLooseningPctOfFunds) {
		if (direction == LimitChangeDirection.TIGHTEN) {
			return 1;
		}
		return isLarge(preview, largeLooseningUsd, largeLooseningPctOfFunds) ? 3 : 2;
	}

}

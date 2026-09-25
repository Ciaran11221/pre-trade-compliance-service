package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.util.Map;

/**
 * What a limit change would do to the funds it can be measured against. usdNewlyAllowed is null
 * when the key is not one of the three issuer-aggregation percentages (ISSUER_LIMIT_PCT,
 * VOTING_LIMIT_PCT, OVER_LIMIT_BUCKET_PCT) -- there is no shared unit to price a change to, say,
 * LOOKBACK_MINUTES in dollars.
 *
 * totalAssetsOfFundsWithIncreasedHeadroom is not part of the API response; RequiredApprovalsCalculator
 * needs it for the "lower of a flat dollar amount or a percentage of the affected funds' assets"
 * threshold and nothing else reads it.
 */
public record ImpactPreview(BigDecimal usdNewlyAllowed, boolean hidesBreach, Map<String, Object> detail,
		BigDecimal totalAssetsOfFundsWithIncreasedHeadroom) {

	public static ImpactPreview notComputable() {
		return new ImpactPreview(null, false, Map.of(), BigDecimal.ZERO);
	}

}

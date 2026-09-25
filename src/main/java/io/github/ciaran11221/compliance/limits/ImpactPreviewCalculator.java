package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.ciaran11221.compliance.rules.IssuerOverLimitCalculator;
import io.github.ciaran11221.compliance.rules.LimitKey;
import io.github.ciaran11221.compliance.rules.Limits;

/**
 * The impact preview for rule 2: for every diversified fund, the over-5% issuer group and the
 * headroom left under the bucket limit, under the currently active limits and under the proposed
 * ones, using current holdings and prices (no pending orders -- this is not a trade). Delegates the
 * actual issuer-aggregation math to IssuerOverLimitCalculator, the same class DiversificationRule
 * uses, so a change here can never disagree with what the rule itself would decide.
 *
 * Only ISSUER_LIMIT_PCT, VOTING_LIMIT_PCT and OVER_LIMIT_BUCKET_PCT can be priced in dollars this
 * way; every other key gets ImpactPreview.notComputable().
 */
@Component
public class ImpactPreviewCalculator {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private static final Set<LimitKey> PRICEABLE_KEYS = Set.of(LimitKey.ISSUER_LIMIT_PCT, LimitKey.VOTING_LIMIT_PCT,
			LimitKey.OVER_LIMIT_BUCKET_PCT);

	private record FundHolding(String fundCode, BigDecimal totalAssets, String ticker, String issuer,
			BigDecimal price, long votingSharesOutstanding, long quantity) {
	}

	private final JdbcTemplate jdbcTemplate;

	public ImpactPreviewCalculator(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public ImpactPreview compute(LimitKey key, BigDecimal newValue, Limits currentLimits) {
		if (!PRICEABLE_KEYS.contains(key)) {
			return ImpactPreview.notComputable();
		}

		BigDecimal issuerLimitCurrent = currentLimits.get(LimitKey.ISSUER_LIMIT_PCT);
		BigDecimal votingLimitCurrent = currentLimits.get(LimitKey.VOTING_LIMIT_PCT);
		BigDecimal bucketPctCurrent = currentLimits.get(LimitKey.OVER_LIMIT_BUCKET_PCT);

		BigDecimal issuerLimitProposed = key == LimitKey.ISSUER_LIMIT_PCT ? newValue : issuerLimitCurrent;
		BigDecimal votingLimitProposed = key == LimitKey.VOTING_LIMIT_PCT ? newValue : votingLimitCurrent;
		BigDecimal bucketPctProposed = key == LimitKey.OVER_LIMIT_BUCKET_PCT ? newValue : bucketPctCurrent;

		List<FundHolding> rows = jdbcTemplate.query("""
				SELECT f.code AS fund_code, f.total_assets AS total_assets, s.ticker AS ticker,
				       s.issuer_name AS issuer_name, s.price AS price,
				       s.voting_shares_outstanding AS voting_shares_outstanding, h.quantity AS quantity
				FROM fund f
				JOIN holding h ON h.fund_id = f.id
				JOIN security s ON s.id = h.security_id
				WHERE f.diversified = true
				ORDER BY f.code, s.ticker
				""",
				(rs, rowNum) -> new FundHolding(rs.getString("fund_code"), rs.getBigDecimal("total_assets"),
						rs.getString("ticker"), rs.getString("issuer_name"), rs.getBigDecimal("price"),
						rs.getLong("voting_shares_outstanding"), rs.getLong("quantity")));

		Map<String, List<FundHolding>> byFund = new LinkedHashMap<>();
		for (FundHolding row : rows) {
			byFund.computeIfAbsent(row.fundCode(), code -> new ArrayList<>()).add(row);
		}

		BigDecimal usdNewlyAllowed = BigDecimal.ZERO;
		BigDecimal totalAssetsWithIncreasedHeadroom = BigDecimal.ZERO;
		boolean hidesBreach = false;
		Map<String, Object> detail = new LinkedHashMap<>();

		for (Map.Entry<String, List<FundHolding>> entry : byFund.entrySet()) {
			List<FundHolding> holdings = entry.getValue();
			BigDecimal totalAssets = holdings.get(0).totalAssets();

			Map<String, Long> positions = new LinkedHashMap<>();
			Map<String, IssuerOverLimitCalculator.SecurityRef> securities = new LinkedHashMap<>();
			for (FundHolding holding : holdings) {
				positions.put(holding.ticker(), holding.quantity());
				securities.put(holding.ticker(), new IssuerOverLimitCalculator.SecurityRef(holding.issuer(),
						holding.price(), holding.votingSharesOutstanding()));
			}

			BigDecimal overSumCurrent = IssuerOverLimitCalculator.overSum(positions, securities::get, totalAssets,
					issuerLimitCurrent, votingLimitCurrent);
			BigDecimal overSumProposed = IssuerOverLimitCalculator.overSum(positions, securities::get, totalAssets,
					issuerLimitProposed, votingLimitProposed);

			BigDecimal headroomOld = headroom(bucketPctCurrent, totalAssets, overSumCurrent);
			BigDecimal headroomNew = headroom(bucketPctProposed, totalAssets, overSumProposed);
			BigDecimal fundImpact = headroomNew.subtract(headroomOld).max(BigDecimal.ZERO);

			boolean breachCurrent = IssuerOverLimitCalculator.overBucket(overSumCurrent, bucketPctCurrent,
					totalAssets);
			boolean breachProposed = IssuerOverLimitCalculator.overBucket(overSumProposed, bucketPctProposed,
					totalAssets);
			boolean fundHidesBreach = breachCurrent && !breachProposed;

			usdNewlyAllowed = usdNewlyAllowed.add(fundImpact);
			if (fundImpact.compareTo(BigDecimal.ZERO) > 0) {
				totalAssetsWithIncreasedHeadroom = totalAssetsWithIncreasedHeadroom.add(totalAssets);
			}
			hidesBreach = hidesBreach || fundHidesBreach;

			Map<String, Object> fundDetail = new LinkedHashMap<>();
			fundDetail.put("overSumCurrent", overSumCurrent);
			fundDetail.put("overSumProposed", overSumProposed);
			fundDetail.put("headroomOld", headroomOld);
			fundDetail.put("headroomNew", headroomNew);
			fundDetail.put("usdNewlyAllowed", fundImpact);
			fundDetail.put("breachCurrent", breachCurrent);
			fundDetail.put("breachProposed", breachProposed);
			detail.put(entry.getKey(), fundDetail);
		}

		return new ImpactPreview(usdNewlyAllowed, hidesBreach, detail, totalAssetsWithIncreasedHeadroom);
	}

	private static BigDecimal headroom(BigDecimal bucketPct, BigDecimal totalAssets, BigDecimal overSum) {
		BigDecimal bucketAmount = bucketPct.multiply(totalAssets).divide(HUNDRED, 4, RoundingMode.HALF_UP);
		return bucketAmount.subtract(overSum).max(BigDecimal.ZERO);
	}

}

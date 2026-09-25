package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a compliance rule needs to evaluate one order, gathered up front so every
 * ComplianceRule implementation stays a pure function of this record: no repository calls, no
 * Spring context, nothing hidden. Security reference data covers every security a rule might need
 * to look at, not just the one on the order, since diversification aggregates across holdings and
 * pending orders too, so it needs price, issuer and voting data for all of them.
 */
public record OrderContext(Fund fund, Order order, Map<String, Long> holdings, Map<String, SecurityInfo> securities,
		Set<String> restrictedSecurities, List<PendingOrder> pendingOrders, Limits limits) {

	public enum Side {

		BUY, SELL

	}

	public record Fund(BigDecimal totalAssets, BigDecimal cash, boolean diversified) {
	}

	public record Order(Side side, String security, long quantity, BigDecimal referencePrice) {
	}

	public record SecurityInfo(String issuer, BigDecimal price, long votingSharesOutstanding, long avgDailyVolume) {
	}

	public record PendingOrder(Side side, String security, long quantity, BigDecimal price) {
	}

	public SecurityInfo securityInfo(String ticker) {
		SecurityInfo info = securities.get(ticker);
		if (info == null) {
			throw new IllegalStateException("No reference data supplied for security " + ticker);
		}
		return info;
	}

	public long holdingQuantity(String ticker) {
		return holdings.getOrDefault(ticker, 0L);
	}

}

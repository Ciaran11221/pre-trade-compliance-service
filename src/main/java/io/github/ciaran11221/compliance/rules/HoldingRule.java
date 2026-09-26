package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * A buy can never take a fund short, so it is not applicable. A sell's quantity must fit within
 * what the fund can actually deliver: the current holding minus every pending SELL of the same
 * security (pending as OrderContext already defines it: PASS-not-yet-filled plus still-open
 * quarantine). A pending BUY adds nothing here, since it has not settled and so is not yet part
 * of the holding a sell would draw down. Selling exactly the available amount passes; selling one
 * share more blocks.
 */
@Component
public class HoldingRule implements ComplianceRule {

	@Override
	public String name() {
		return "holding";
	}

	@Override
	public RuleResult evaluate(OrderContext context) {
		if (context.order().side() == OrderContext.Side.BUY) {
			return new RuleResult(name(), RuleOutcome.NOT_APPLICABLE,
					"a buy cannot take the fund short, so the holding rule does not apply.", null, null);
		}

		String ticker = context.order().security();
		long holding = context.holdingQuantity(ticker);
		long pendingSells = context.pendingOrders()
			.stream()
			.filter(pending -> pending.side() == OrderContext.Side.SELL && pending.security().equals(ticker))
			.mapToLong(OrderContext.PendingOrder::quantity)
			.sum();
		long available = holding - pendingSells;
		long requested = context.order().quantity();

		boolean blocked = requested > available;
		String reason = String.format(Locale.ROOT,
				"sell of %,d shares of %s %s the %,d shares available (holding of %,d minus %,d in pending sells).",
				requested, ticker, blocked ? "exceeds" : "fits within", available, holding, pendingSells);

		return new RuleResult(name(), blocked ? RuleOutcome.BLOCK : RuleOutcome.PASS, reason,
				BigDecimal.valueOf(requested), BigDecimal.valueOf(available));
	}

}

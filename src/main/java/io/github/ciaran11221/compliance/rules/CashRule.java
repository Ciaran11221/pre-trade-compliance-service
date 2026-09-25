package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * A sell never needs cash to settle, so it is not applicable. A buy's value (quantity x reference
 * price) must fit within the fund's cash after every pending buy's own value has already been set
 * aside; a pending sell frees nothing up until it actually fills, so it is not counted here.
 */
@Component
public class CashRule implements ComplianceRule {

	@Override
	public String name() {
		return "cash";
	}

	@Override
	public RuleResult evaluate(OrderContext context) {
		if (context.order().side() == OrderContext.Side.SELL) {
			return new RuleResult(name(), RuleOutcome.NOT_APPLICABLE, "cash is only checked for a buy order.", null,
					null);
		}

		BigDecimal orderValue = context.order()
			.referencePrice()
			.multiply(BigDecimal.valueOf(context.order().quantity()));

		BigDecimal pendingBuyValue = context.pendingOrders()
			.stream()
			.filter(pending -> pending.side() == OrderContext.Side.BUY)
			.map(pending -> pending.price().multiply(BigDecimal.valueOf(pending.quantity())))
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		BigDecimal available = context.fund().cash().subtract(pendingBuyValue);

		boolean blocked = orderValue.compareTo(available) > 0;
		String reason = String.format(Locale.ROOT, 
				"order value $%,.2f %s the $%,.2f cash available (fund cash $%,.2f minus $%,.2f of pending buys).",
				orderValue, blocked ? "exceeds" : "fits within", available, context.fund().cash(), pendingBuyValue);

		return new RuleResult(name(), blocked ? RuleOutcome.BLOCK : RuleOutcome.PASS, reason, orderValue, available);
	}

}

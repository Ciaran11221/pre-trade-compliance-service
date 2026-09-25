package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * An order worth more than ORDER_SIZE_ADV_PCT of the security's average daily volume goes to
 * review, on either side. The test is written as quantity x 100 > ORDER_SIZE_ADV_PCT x average
 * daily volume so no division is needed to decide it.
 */
@Component
public class OrderSizeRule implements ComplianceRule {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	@Override
	public String name() {
		return "order-size";
	}

	@Override
	public RuleResult evaluate(OrderContext context) {
		OrderContext.SecurityInfo security = context.securityInfo(context.order().security());
		BigDecimal quantity = BigDecimal.valueOf(context.order().quantity());
		BigDecimal avgDailyVolume = BigDecimal.valueOf(security.avgDailyVolume());
		BigDecimal advPct = context.limits().get(LimitKey.ORDER_SIZE_ADV_PCT);

		boolean review = quantity.multiply(HUNDRED).compareTo(advPct.multiply(avgDailyVolume)) > 0;

		BigDecimal measuredPct = quantity.multiply(HUNDRED).divide(avgDailyVolume, 4, RoundingMode.HALF_UP);

		String reason = String.format(Locale.ROOT, 
				"order of %,d shares is %s%% of the %,d-share average daily volume, %s the %s%% threshold.",
				context.order().quantity(), measuredPct.stripTrailingZeros().toPlainString(),
				security.avgDailyVolume(), review ? "above" : "at or below",
				advPct.stripTrailingZeros().toPlainString());

		return new RuleResult(name(), review ? RuleOutcome.REVIEW : RuleOutcome.PASS, reason, measuredPct, advPct);
	}

}

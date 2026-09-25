package io.github.ciaran11221.compliance.rules;

import org.springframework.stereotype.Component;

/**
 * A security on the restricted list blocks any order in it, buy or sell, regardless of price or
 * size.
 */
@Component
public class RestrictedListRule implements ComplianceRule {

	@Override
	public String name() {
		return "restricted-list";
	}

	@Override
	public RuleResult evaluate(OrderContext context) {
		String ticker = context.order().security();
		if (context.restrictedSecurities().contains(ticker)) {
			return new RuleResult(name(), RuleOutcome.BLOCK,
					ticker + " is on the restricted list, so this order is blocked regardless of side or size.", null,
					null);
		}
		return new RuleResult(name(), RuleOutcome.PASS, ticker + " is not on the restricted list.", null, null);
	}

}

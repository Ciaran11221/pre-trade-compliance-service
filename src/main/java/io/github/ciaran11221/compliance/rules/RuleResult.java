package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;

/**
 * One rule's verdict on one order: what it decided, why in a sentence a compliance officer can
 * read on its own, and the numbers behind it when there are any. measuredValue and limitValue are
 * null for a rule like restricted-list, where there is no single number to report.
 */
public record RuleResult(String ruleName, RuleOutcome outcome, String reason, BigDecimal measuredValue,
		BigDecimal limitValue) {
}

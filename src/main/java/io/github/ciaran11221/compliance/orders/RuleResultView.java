package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;

public record RuleResultView(String ruleName, String outcome, String reason, BigDecimal measuredValue,
		BigDecimal limitValue) {
}

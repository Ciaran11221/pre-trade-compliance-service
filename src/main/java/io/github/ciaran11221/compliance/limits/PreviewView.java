package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.util.Map;

public record PreviewView(BigDecimal usdNewlyAllowed, boolean hidesBreach, int requiredApprovals,
		Map<String, Object> detail) {
}

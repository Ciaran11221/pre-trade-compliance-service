package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;

public record LimitChangeRequestBody(String key, BigDecimal newValue, String reason) {
}

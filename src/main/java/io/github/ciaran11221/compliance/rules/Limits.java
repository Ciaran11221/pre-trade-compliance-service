package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * An immutable snapshot of every firm limit at one point in time, keyed by LimitKey.
 */
public record Limits(Map<LimitKey, BigDecimal> values) {

	public Limits {
		values = Map.copyOf(values);
	}

	public static Limits defaults() {
		Map<LimitKey, BigDecimal> values = new EnumMap<>(LimitKey.class);
		for (LimitKey key : LimitKey.values()) {
			values.put(key, key.defaultValue());
		}
		return new Limits(values);
	}

	public BigDecimal get(LimitKey key) {
		BigDecimal value = values.get(key);
		if (value == null) {
			throw new IllegalStateException("No value set for " + key);
		}
		return value;
	}

}

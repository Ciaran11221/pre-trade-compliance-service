package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;

/**
 * The ten firm-limit setting keys, each carrying the default value V3__setting_defaults.sql
 * inserts for it. LimitKeyDefaultsTest reads the seeded database and asserts these never drift
 * apart from what that migration actually loads.
 */
public enum LimitKey {

	ISSUER_LIMIT_PCT(new BigDecimal("5")),
	VOTING_LIMIT_PCT(new BigDecimal("10")),
	OVER_LIMIT_BUCKET_PCT(new BigDecimal("25")),
	ORDER_SIZE_ADV_PCT(new BigDecimal("10")),
	LOOKBACK_MINUTES(new BigDecimal("5")),
	SIMILARITY_PCT(new BigDecimal("10")),
	QUARANTINE_EXPIRY_MINUTES(new BigDecimal("30")),
	LARGE_LOOSENING_USD(new BigDecimal("50000000")),
	LARGE_LOOSENING_PCT_OF_FUNDS(new BigDecimal("1")),
	COOLING_OFF_HOURS(new BigDecimal("24"));

	private final BigDecimal defaultValue;

	LimitKey(BigDecimal defaultValue) {
		this.defaultValue = defaultValue;
	}

	public BigDecimal defaultValue() {
		return defaultValue;
	}

}

package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The ten firm-limit setting keys, each carrying the default value V3__setting_defaults.sql
 * inserts for it. LimitKeyDefaultsTest reads the seeded database and asserts these never drift
 * apart from what that migration actually loads.
 *
 * Each key also carries the direction that loosens it (raising the value or lowering it) and the
 * hard bounds a limit-change request may never cross, in code rather than as a setting: the legal
 * maximums of the US 1940 Act (issuer, voting and over-limit-bucket percentages) plus a handful of
 * bounds that keep a value meaningful (a lookback of zero minutes, for example, checks nothing).
 * See Bound.violatedBy and firstViolatedBound.
 */
public enum LimitKey {

	ISSUER_LIMIT_PCT(new BigDecimal("5"), LooserDirection.HIGHER, Bound.maxInclusive(new BigDecimal("5")),
			Bound.minExclusive(BigDecimal.ZERO)),
	VOTING_LIMIT_PCT(new BigDecimal("10"), LooserDirection.HIGHER, Bound.maxInclusive(new BigDecimal("10")),
			Bound.minExclusive(BigDecimal.ZERO)),
	OVER_LIMIT_BUCKET_PCT(new BigDecimal("25"), LooserDirection.HIGHER, Bound.maxInclusive(new BigDecimal("25")),
			Bound.minExclusive(BigDecimal.ZERO)),
	ORDER_SIZE_ADV_PCT(new BigDecimal("10"), LooserDirection.HIGHER, Bound.maxInclusive(new BigDecimal("100")),
			Bound.minExclusive(BigDecimal.ZERO)),
	LOOKBACK_MINUTES(new BigDecimal("5"), LooserDirection.LOWER, Bound.minInclusive(BigDecimal.ONE)),
	SIMILARITY_PCT(new BigDecimal("10"), LooserDirection.LOWER, Bound.minInclusive(BigDecimal.ZERO)),
	QUARANTINE_EXPIRY_MINUTES(new BigDecimal("30"), LooserDirection.HIGHER, Bound.maxInclusive(new BigDecimal("1440")),
			Bound.minExclusive(BigDecimal.ZERO)),
	LARGE_LOOSENING_USD(new BigDecimal("50000000"), LooserDirection.HIGHER, Bound.minExclusive(BigDecimal.ZERO)),
	LARGE_LOOSENING_PCT_OF_FUNDS(new BigDecimal("1"), LooserDirection.HIGHER, Bound.minExclusive(BigDecimal.ZERO)),
	COOLING_OFF_HOURS(new BigDecimal("24"), LooserDirection.LOWER, Bound.minInclusive(BigDecimal.ONE));

	/**
	 * Which direction of change makes this limit looser (frees up more money or weakens a safety
	 * check). Used to classify a limit-change request as TIGHTEN or LOOSEN.
	 */
	public enum LooserDirection {

		HIGHER, LOWER

	}

	/**
	 * One hard numeric bound a requested value may never cross. MAX_INCLUSIVE and MIN_INCLUSIVE
	 * bounds may sit exactly at the bound; MIN_EXCLUSIVE may not (used for "must be a positive
	 * number", where zero itself makes no sense).
	 */
	public record Bound(Kind kind, BigDecimal value) {

		public enum Kind {

			MAX_INCLUSIVE, MIN_INCLUSIVE, MIN_EXCLUSIVE

		}

		public static Bound maxInclusive(BigDecimal value) {
			return new Bound(Kind.MAX_INCLUSIVE, value);
		}

		public static Bound minInclusive(BigDecimal value) {
			return new Bound(Kind.MIN_INCLUSIVE, value);
		}

		public static Bound minExclusive(BigDecimal value) {
			return new Bound(Kind.MIN_EXCLUSIVE, value);
		}

		public boolean violatedBy(BigDecimal requested) {
			return switch (kind) {
				case MAX_INCLUSIVE -> requested.compareTo(value) > 0;
				case MIN_INCLUSIVE -> requested.compareTo(value) < 0;
				case MIN_EXCLUSIVE -> requested.compareTo(value) <= 0;
			};
		}

		public String describe(LimitKey key) {
			String plainValue = value.compareTo(BigDecimal.ZERO) == 0 ? "0"
					: value.stripTrailingZeros().toPlainString();
			String comparator = switch (kind) {
				case MAX_INCLUSIVE -> "<=";
				case MIN_INCLUSIVE -> ">=";
				case MIN_EXCLUSIVE -> ">";
			};
			return key.name() + " must be " + comparator + " " + plainValue;
		}

	}

	private final BigDecimal defaultValue;

	private final LooserDirection looserDirection;

	private final List<Bound> bounds;

	LimitKey(BigDecimal defaultValue, LooserDirection looserDirection, Bound... bounds) {
		this.defaultValue = defaultValue;
		this.looserDirection = looserDirection;
		this.bounds = List.of(bounds);
	}

	public BigDecimal defaultValue() {
		return defaultValue;
	}

	public LooserDirection looserDirection() {
		return looserDirection;
	}

	public List<Bound> bounds() {
		return bounds;
	}

	/** The first bound (in declaration order) a requested value violates, if any. */
	public Optional<Bound> firstViolatedBound(BigDecimal requested) {
		return bounds.stream().filter(bound -> bound.violatedBy(requested)).findFirst();
	}

	/**
	 * Whether moving from oldValue to newValue loosens this limit. Callers must have already
	 * rejected oldValue.equals(newValue) -- there is no third answer here, only looser or tighter.
	 */
	public boolean isLoosening(BigDecimal oldValue, BigDecimal newValue) {
		int comparison = newValue.compareTo(oldValue);
		return looserDirection == LooserDirection.HIGHER ? comparison > 0 : comparison < 0;
	}

}

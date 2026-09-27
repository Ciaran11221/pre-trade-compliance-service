package io.github.ciaran11221.compliance.cucumber;

import java.math.BigDecimal;

import io.cucumber.java.ParameterType;

/**
 * Custom Cucumber Expression types for the money/percent/share-count/staff-id shapes the plain-
 * English sentences (M10, issue #31) use: "$45,000,000" (commas removed, exact BigDecimal),
 * "20.5%" (BigDecimal, the % removed), "1,050 shares" (commas removed, long), and a staff id
 * ("anne", "sup-1") narrow enough that "{staff}'s order" never swallows the apostrophe the way the
 * built-in {word} type ([^\s]+) would.
 */
public class ParameterTypes {

	@ParameterType(value = "\\$[0-9,]+(?:\\.[0-9]+)?", name = "money")
	public BigDecimal money(String value) {
		return new BigDecimal(value.substring(1).replace(",", ""));
	}

	@ParameterType(value = "[0-9]+(?:\\.[0-9]+)?%", name = "pct")
	public BigDecimal pct(String value) {
		return new BigDecimal(value.substring(0, value.length() - 1));
	}

	@ParameterType(value = "[0-9][0-9,]*", name = "shareCount")
	public long shareCount(String value) {
		return Long.parseLong(value.replace(",", ""));
	}

	@ParameterType(value = "[a-z][a-z0-9-]*", name = "staff")
	public String staff(String value) {
		return value;
	}

}

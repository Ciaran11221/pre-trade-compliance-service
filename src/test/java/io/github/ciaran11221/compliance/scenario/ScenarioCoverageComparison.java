package io.github.ciaran11221.compliance.scenario;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Compares tags that RequiredCoverage providers say must be covered against tags the scenario
 * corpus actually covers. Kept free of ServiceLoader and file I/O (plain sets in, plain set out)
 * so ScenarioCoverageComparisonTest can prove it catches a missing tag without a real provider or
 * a real corpus -- same shape as RouteSetComparison for the route matrix.
 */
public record ScenarioCoverageComparison(Set<String> missing) {

	public static ScenarioCoverageComparison compare(Set<String> required, Set<String> covered) {
		Set<String> missing = new LinkedHashSet<>(required);
		missing.removeAll(covered);
		return new ScenarioCoverageComparison(missing);
	}

	public boolean matches() {
		return missing.isEmpty();
	}

	public String describe() {
		return "required tags with no covering scenario: " + missing;
	}

}

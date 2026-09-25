package io.github.ciaran11221.compliance.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Compares the routes Spring actually registered against the routes route-access.csv declares.
 * Kept free of any Spring dependency (plain sets in, plain sets out) so RouteSetComparisonTest can
 * exercise it without an application context, proving it actually catches a missing route -- see
 * that class for why RouteAccessMatrixTest's Test A can be trusted.
 */
public record RouteSetComparison(Set<Route> onlyInActual, Set<Route> onlyInCsv) {

	public static RouteSetComparison compare(Set<Route> actual, Set<Route> csv) {
		Set<Route> onlyInActual = new LinkedHashSet<>(actual);
		onlyInActual.removeAll(csv);
		Set<Route> onlyInCsv = new LinkedHashSet<>(csv);
		onlyInCsv.removeAll(actual);
		return new RouteSetComparison(onlyInActual, onlyInCsv);
	}

	public boolean matches() {
		return onlyInActual.isEmpty() && onlyInCsv.isEmpty();
	}

	public String describe() {
		return "routes Spring registered but missing from route-access.csv: " + onlyInActual
				+ "; routes in route-access.csv that nothing registers: " + onlyInCsv;
	}

}

package io.github.ciaran11221.compliance.security;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meta-test: proves RouteAccessMatrixTest's Test A actually bites. No Spring context here on
 * purpose -- RouteSetComparison is a plain function of two sets, so a controller that forgets to
 * update route-access.csv can be shown to fail the comparison without booting the application.
 * A real unguarded controller is never left in the codebase to prove this; the "extra route" is
 * synthetic, right here.
 */
class RouteSetComparisonTest {

	@Test
	void matchesWhenBothSetsAreIdentical() {
		Set<Route> routes = Set.of(new Route("GET", "/api/me"));

		assertThat(RouteSetComparison.compare(routes, routes).matches()).isTrue();
	}

	@Test
	void aRouteRegisteredButMissingFromTheCsvFailsTheMatch() {
		Set<Route> actual = Set.of(new Route("GET", "/api/me"), new Route("POST", "/api/orders"));
		Set<Route> csv = Set.of(new Route("GET", "/api/me"));

		RouteSetComparison result = RouteSetComparison.compare(actual, csv);

		assertThat(result.matches()).isFalse();
		assertThat(result.onlyInActual()).containsExactly(new Route("POST", "/api/orders"));
		assertThat(result.describe()).contains("POST").contains("/api/orders");
	}

	@Test
	void aCsvRowThatNothingRegistersFailsTheMatch() {
		Set<Route> actual = Set.of(new Route("GET", "/api/me"));
		Set<Route> csv = Set.of(new Route("GET", "/api/me"), new Route("DELETE", "/api/ghost"));

		RouteSetComparison result = RouteSetComparison.compare(actual, csv);

		assertThat(result.matches()).isFalse();
		assertThat(result.onlyInCsv()).containsExactly(new Route("DELETE", "/api/ghost"));
	}

}

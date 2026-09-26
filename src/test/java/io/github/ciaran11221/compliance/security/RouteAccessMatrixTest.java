package io.github.ciaran11221.compliance.security;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement's "Route matrix" tests A, B and C, all driven off route-access.csv. Test A is
 * proven to actually bite by RouteSetComparisonTest (no Spring context needed for that part).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RouteAccessMatrixTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final List<String> ROLES = List.of("TRADER", "SUPERVISOR", "COMPLIANCE", "EXECUTIVE");

	// Any seeded staff id works: role comes from the token's "roles" claim, not from the staff
	// table, so which real person we borrow the id from is not part of what this test checks.
	private static final String KNOWN_STAFF_ID = "anne";

	@LocalServerPort
	private int port;

	// Actuator's own @ControllerEndpoint support registers a second RequestMappingHandlerMapping
	// bean ("controllerEndpointHandlerMapping"); the one that carries our own @RestController
	// routes is Spring MVC's standard "requestMappingHandlerMapping".
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	private final RestClient restClient = RestClient.create();

	@Test
	void everyRegisteredRouteIsInTheCsvAndViceVersa() {
		Map<Route, Method> actualRoutes = ControllerRoutes.from(handlerMapping);
		var expectedRoutes = RouteAccessCsv.read("route-access.csv").stream()
			.map(row -> new Route(row.method(), row.path()))
			.collect(Collectors.toUnmodifiableSet());

		RouteSetComparison comparison = RouteSetComparison.compare(actualRoutes.keySet(), expectedRoutes);

		assertThat(comparison.matches()).as(comparison.describe()).isTrue();
	}

	@Test
	void everyHandlerMethodDeclaresAnAccessDecision() {
		Map<Route, Method> routes = ControllerRoutes.from(handlerMapping);

		assertThat(routes).isNotEmpty();
		routes.forEach((route, method) -> assertThat(
				method.isAnnotationPresent(PreAuthorize.class) || method.isAnnotationPresent(PublicEndpoint.class))
			.as("handler for %s (%s) is missing @PreAuthorize or @PublicEndpoint", route, method)
			.isTrue());
	}

	@ParameterizedTest(name = "{0} {1} with only role {2} -> allowed={3}")
	@MethodSource("csvRowsCrossRoles")
	void roleAccessMatchesTheCsv(String method, String path, String role, boolean allowed) throws Exception {
		String token = TokenTool.signedToken(KNOWN_STAFF_ID, List.of(role), 5, TEST_SECRET);

		ResponseEntity<String> response = call(HttpMethod.valueOf(method), path, token);

		if (allowed) {
			assertThat(response.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
		}
		else {
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	static List<Arguments> csvRowsCrossRoles() {
		List<Arguments> args = new ArrayList<>();
		for (RouteAccessCsv.Row row : RouteAccessCsv.read("route-access.csv")) {
			for (String role : ROLES) {
				boolean allowed = row.isPublic() || row.allowsRole(role);
				args.add(Arguments.of(row.method(), row.path(), role, allowed));
			}
		}
		return args;
	}

	// route-access.csv carries the literal path template ("/api/limit-changes/{id}/approvals"),
	// braces included, since that is what a route is keyed by. RestClient's uri(String) overload
	// treats "{...}" as a URI template variable to expand, not literal text, and errors ("Not
	// enough variable values available to expand 'id'") when none is supplied. Substituting a
	// concrete id here -- one that does not exist, so every call 404s or is turned away by
	// @PreAuthorize before that -- turns the CSV path into a plain literal URL. It is substituted
	// after the braces are gone, so uri(String) never sees a template to expand.
	private static final String SUBSTITUTE_ID = "999999";

	private ResponseEntity<String> call(HttpMethod method, String path, String token) {
		// GET /api/funds/{fundId}/orders (orders package) is the first route whose path variable
		// isn't named "{id}" -- substituted the same way and for the same reason as "{id}" above,
		// rather than loosening the check by skipping unresolved braces.
		String resolvedPath = path.replace("{id}", SUBSTITUTE_ID).replace("{fundId}", SUBSTITUTE_ID);
		return restClient.method(method)
			.uri("http://localhost:" + port + resolvedPath)
			.headers(headers -> headers.setBearerAuth(token))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

}

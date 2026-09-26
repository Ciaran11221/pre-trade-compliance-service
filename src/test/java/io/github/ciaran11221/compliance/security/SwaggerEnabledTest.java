package io.github.ciaran11221.compliance.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the springdoc switches the local profile sets: the API description loads without a token,
 * lists every route in route-access.csv, and offers a bearer-token Authorize button.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true" })
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SwaggerEnabledTest {

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void swaggerPageLoadsWithoutAToken() {
		assertThat(get("/swagger-ui/index.html").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void apiDescriptionListsEveryRouteInTheAccessTable() {
		JsonNode paths = apiDescription().get("paths");
		assertThat(paths).as("paths in /v3/api-docs").isNotNull();
		List<RouteAccessCsv.Row> rows = RouteAccessCsv.read("route-access.csv");
		for (RouteAccessCsv.Row row : rows) {
			assertThat(paths.has(row.path())).as("route-access.csv path %s in /v3/api-docs", row.path()).isTrue();
		}
	}

	@Test
	void apiDescriptionOffersABearerTokenScheme() {
		JsonNode scheme = apiDescription().path("components").path("securitySchemes").path("bearerAuth");
		assertThat(scheme.path("type").asString()).isEqualTo("http");
		assertThat(scheme.path("scheme").asString()).isEqualTo("bearer");
	}

	private JsonNode apiDescription() {
		ResponseEntity<String> response = get("/v3/api-docs");
		assertThat(response.getStatusCode()).as("/v3/api-docs: %s", response.getBody()).isEqualTo(HttpStatus.OK);
		return objectMapper.readTree(response.getBody());
	}

	private ResponseEntity<String> get(String path) {
		return restClient.get()
			.uri("http://localhost:" + port + path)
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode()).body(res.bodyTo(String.class)));
	}

}

package io.github.ciaran11221.compliance.security;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Swagger API docs availability. The docs are disabled by default (test profile) and
 * enabled only on the local profile.
 */
class SwaggerApiDocsTest {

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Default/test profile: the Swagger endpoints do not exist and return 404.
	 */
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
	@Import(TestcontainersConfig.class)
	@ActiveProfiles("test")
	class WhenSwaggerDisabled {

		@LocalServerPort
		private int port;

		private final RestClient restClient = RestClient.create();

		@Test
		void apiDocsEndpointReturns404() {
			ResponseEntity<String> response = restClient.get()
				.uri("http://localhost:" + port + "/v3/api-docs")
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.body(res.bodyTo(String.class)));

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		void swaggerUiEndpointReturns404() {
			ResponseEntity<String> response = restClient.get()
				.uri("http://localhost:" + port + "/swagger-ui/index.html")
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.body(res.bodyTo(String.class)));

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

	}

	/**
	 * With springdoc properties enabled: the API docs endpoint returns 200 without requiring a
	 * token, and includes all routes from route-access.csv in the paths.
	 */
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true" })
	@Import(TestcontainersConfig.class)
	@ActiveProfiles("test")
	class WhenSwaggerEnabled {

		@LocalServerPort
		private int port;

		private final RestClient restClient = RestClient.create();

		private final ObjectMapper objectMapper = new ObjectMapper();

		@Test
		void apiDocsEndpointReturns200WithoutToken() {
			ResponseEntity<String> response = restClient.get()
				.uri("http://localhost:" + port + "/v3/api-docs")
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.body(res.bodyTo(String.class)));

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		@Test
		void apiDocsIncludesAllRoutesFromCsv() throws Exception {
			ResponseEntity<String> response = restClient.get()
				.uri("http://localhost:" + port + "/v3/api-docs")
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.body(res.bodyTo(String.class)));

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

			JsonNode root = objectMapper.readTree(response.getBody());
			JsonNode paths = root.get("paths");
			assertThat(paths).isNotNull();

			@SuppressWarnings("unchecked")
			Map<String, Object> pathsMap = objectMapper.convertValue(paths, Map.class);
			List<String> apiPaths = pathsMap.keySet().stream()
				.collect(Collectors.toList());

			List<RouteAccessCsv.Row> csvRows = RouteAccessCsv.read("route-access.csv");
			for (RouteAccessCsv.Row row : csvRows) {
				assertThat(apiPaths).as("path %s from route-access.csv", row.path())
					.contains(row.path());
			}
		}

		@Test
		void apiDocsIncludesBearerAuthSecurityScheme() throws Exception {
			ResponseEntity<String> response = restClient.get()
				.uri("http://localhost:" + port + "/v3/api-docs")
				.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
					.body(res.bodyTo(String.class)));

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

			JsonNode root = objectMapper.readTree(response.getBody());
			JsonNode components = root.get("components");
			assertThat(components).isNotNull();

			JsonNode securitySchemes = components.get("securitySchemes");
			assertThat(securitySchemes).isNotNull();
			assertThat(securitySchemes.has("bearerAuth")).isTrue();

			JsonNode bearerAuth = securitySchemes.get("bearerAuth");
			assertThat(bearerAuth.get("type").asText()).isEqualTo("http");
			assertThat(bearerAuth.get("scheme").asText()).isEqualTo("bearer");
			assertThat(bearerAuth.get("bearerFormat").asText()).isEqualTo("JWT");
		}

	}

}

package io.github.ciaran11221.compliance;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

	@LocalServerPort
	private int port;

	@Test
	void healthEndpointReturnsUp() {
		ResponseEntity<Map<String, Object>> response = RestClient.create()
			.get()
			.uri("http://localhost:" + port + "/actuator/health")
			.retrieve()
			.toEntity(new ParameterizedTypeReference<>() {});

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "UP");
	}

}

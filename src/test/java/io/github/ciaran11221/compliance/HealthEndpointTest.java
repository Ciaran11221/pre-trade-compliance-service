package io.github.ciaran11221.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

	@LocalServerPort
	private int port;

	@Test
	void healthEndpointReturnsUp() {
		RestClient restClient = RestClient.create();
		String response = restClient.get()
			.uri("http://localhost:" + port + "/actuator/health")
			.retrieve()
			.body(String.class);

		assertThat(response).contains("\"status\":\"UP\"");
	}

}

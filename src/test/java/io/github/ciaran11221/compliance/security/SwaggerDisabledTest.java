package io.github.ciaran11221.compliance.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outside the local profile the API description and the Swagger page do not exist. Without a
 * token they answer exactly like a made-up path (the deny-by-default rule answers 401 before
 * routing), so their presence can't be detected; with a valid token they are 404.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SwaggerDisabledTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@Test
	void withoutATokenTheyAnswerLikeAPathThatDoesNotExist() {
		HttpStatus madeUp = status("/no-such-path", null);
		assertThat(status("/v3/api-docs", null)).isEqualTo(madeUp);
		assertThat(status("/swagger-ui/index.html", null)).isEqualTo(madeUp);
	}

	@Test
	void withAValidTokenTheyAreNotFound() throws Exception {
		String token = TokenTool.signedToken("anne", List.of("TRADER"), 5, TEST_SECRET);
		assertThat(status("/v3/api-docs", token)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(status("/swagger-ui/index.html", token)).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private HttpStatus status(String path, String token) {
		return restClient.get()
			.uri("http://localhost:" + port + path)
			.headers(h -> {
				if (token != null) {
					h.setBearerAuth(token);
				}
			})
			.exchange((req, res) -> HttpStatus.valueOf(res.getStatusCode().value()));
	}

}

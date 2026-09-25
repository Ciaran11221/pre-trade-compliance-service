package io.github.ciaran11221.compliance.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct unit test of the startup check in SecurityConfig.jwtSigningKey (requirement 1) -- no
 * Spring context needed, this exercises exactly the code Spring calls as a bean factory method,
 * so a blank/missing secret failing this method is exactly what fails the application to start.
 * See NoDefaultJwtSecretTest for the same behaviour proven at the Spring context level.
 */
class JwtSigningKeyTest {

	private final SecurityConfig securityConfig = new SecurityConfig();

	@Test
	void blankSecretFailsWithAClearMessage() {
		assertThatThrownBy(() -> securityConfig.jwtSigningKey(""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("compliance.security.jwt-secret")
			.hasMessageContaining("JWT_SECRET");
	}

	@Test
	void nullSecretFailsWithAClearMessage() {
		assertThatThrownBy(() -> securityConfig.jwtSigningKey(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("compliance.security.jwt-secret");
	}

	@Test
	void realSecretProducesAUsableKey() {
		assertThat(securityConfig.jwtSigningKey("a-real-secret-value").getAlgorithm()).isEqualTo("HmacSHA256");
	}

}

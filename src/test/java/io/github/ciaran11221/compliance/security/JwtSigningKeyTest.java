package io.github.ciaran11221.compliance.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct unit test of the startup check in SecurityConfig.jwtSigningKey -- no Spring context
 * needed, this exercises exactly the code Spring calls as a bean factory method, so a
 * blank/missing/too-short secret failing this method is exactly what fails the application to
 * start. See NoDefaultJwtSecretTest for the same behaviour proven at the Spring context level.
 */
class JwtSigningKeyTest {

	// 31 and 32 ASCII bytes either side of the HS256 minimum (32 bytes / 256 bits, RFC 7518
	// section 3.2).
	private static final String SECRET_31_BYTES = "a".repeat(31);

	private static final String SECRET_32_BYTES = "a".repeat(32);

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
	void thirtyOneByteSecretFailsWithTheMinimumInTheMessage() {
		assertThatThrownBy(() -> securityConfig.jwtSigningKey(SECRET_31_BYTES))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("31")
			.hasMessageContaining("32");
	}

	@Test
	void thirtyTwoByteSecretProducesAUsableKey() {
		assertThat(securityConfig.jwtSigningKey(SECRET_32_BYTES).getAlgorithm()).isEqualTo("HmacSHA256");
	}

}

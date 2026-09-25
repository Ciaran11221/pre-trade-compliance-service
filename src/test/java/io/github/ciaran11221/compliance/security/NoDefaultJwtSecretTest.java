package io.github.ciaran11221.compliance.security;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proven at the Spring context level rather than as a plain unit test (see JwtSigningKeyTest for
 * that): the application must fail to start without compliance.security.jwt-secret set. Uses
 * ApplicationContextRunner with a minimal nested configuration -- not the full application -- so
 * this does not need a database or Testcontainers; only the one bean under test (and its real
 * @Value-driven property resolution) is in play.
 */
class NoDefaultJwtSecretTest {

	@Configuration
	static class OnlyTheSigningKeyBean {

		@Bean
		SecretKey jwtSigningKey(@Value("${compliance.security.jwt-secret:}") String secret) {
			return new SecurityConfig().jwtSigningKey(secret);
		}

	}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(OnlyTheSigningKeyBean.class);

	@Test
	void contextFailsToStartWithNoSecretConfiguredAtAll() {
		contextRunner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalStateException.class)
				.rootCause()
				.hasMessageContaining("compliance.security.jwt-secret")
				.hasMessageContaining("JWT_SECRET");
		});
	}

	@Test
	void contextFailsToStartWithABlankSecret() {
		contextRunner.withPropertyValues("compliance.security.jwt-secret=")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void contextStartsWithARealSecret() {
		// 32 ASCII bytes: the HS256 minimum (see SecurityConfig.MIN_SECRET_BYTES).
		contextRunner.withPropertyValues("compliance.security.jwt-secret=" + "a".repeat(32))
			.run(context -> assertThat(context).hasNotFailed());
	}

}

package io.github.ciaran11221.compliance.cucumber;

import java.time.Instant;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import io.cucumber.spring.CucumberContextConfiguration;

import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;

/**
 * Wires the one Spring Boot context every scenario in src/test/resources/features/*.feature runs
 * against (M10 part 2, issue #31): a real Testcontainers Postgres (Trap #8 -- @Import
 * TestcontainersConfig exactly like every other database test, so this never touches the
 * docker-compose Postgres on localhost:5432), HTTP over a random port (no MockMvc, since tokens go
 * through the real security filter chain), and the same MutableClock override every HTTP-level
 * test class in this repo uses (OrderIntakeHttpTest, QuarantineHttpTest, LimitChangeScenarioTest).
 *
 * <p>
 * Exactly one class in the suite may carry @CucumberContextConfiguration. Every step-definition
 * class below reaches this same context by constructor/field injection: cucumber-spring backs its
 * "cucumber-glue" scope with the ApplicationContext this class describes, built once per scenario.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, CucumberSpringConfiguration.ClockOverride.class })
@ActiveProfiles("test")
public class CucumberSpringConfiguration {

	static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockOverride {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(FIXED_START);
		}

	}

}

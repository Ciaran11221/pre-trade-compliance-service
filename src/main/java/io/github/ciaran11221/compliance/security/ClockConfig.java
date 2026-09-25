package io.github.ciaran11221.compliance.security;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable Clock, so "out of office right now" (requirement 6, see MeController) can
 * be tested against a fixed instant instead of the real wall clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}

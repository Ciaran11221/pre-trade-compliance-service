package io.github.ciaran11221.compliance.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Testcontainers setup for every Spring Boot test in this module.
 * Import this into a test class alongside {@code @ActiveProfiles("test")}
 * (see application-test.yml, which points Flyway at the seed data too) to
 * get a real Postgres 17 database wired up automatically via
 * {@code @ServiceConnection}, no datasource properties to set by hand.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17-alpine");
	}

}

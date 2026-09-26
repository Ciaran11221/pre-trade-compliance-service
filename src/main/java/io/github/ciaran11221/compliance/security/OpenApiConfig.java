package io.github.ciaran11221.compliance.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI configuration for the Swagger UI and API documentation. Conditionally enabled when
 * springdoc.api-docs.enabled is true (local profile only).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

	@Bean
	OpenAPI openAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("Pre-Trade Compliance Service")
				.description("Pre-trade compliance checks for fund orders")
				.version("0.0.1"))
			.addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
			.components(new io.swagger.v3.oas.models.Components()
				.addSecuritySchemes("bearerAuth",
					new SecurityScheme()
						.type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("Enter a valid JWT token")));
	}

}

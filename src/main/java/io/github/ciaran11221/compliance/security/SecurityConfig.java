package io.github.ciaran11221.compliance.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server security for this service (milestone M3): every request carries a signed JWT
 * or it is rejected, deny by default, checked here once rather than by each controller. See
 * KnownStaffAuthorizationManager for the "known staff only" rule and the ProblemDetail* classes
 * for the 401/403 response bodies.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	/**
	 * Requirement 1: no default secret anywhere in application.yml. The property resolves to an
	 * empty string when JWT_SECRET is not set, and an empty string is treated the same as a
	 * missing one -- this bean method fails, which fails the whole application context, which is
	 * the "clear message on startup" check. See JwtSigningKeyTest and NoDefaultJwtSecretTest.
	 */
	@Bean
	SecretKey jwtSigningKey(@Value("${compliance.security.jwt-secret:}") String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException(
					"compliance.security.jwt-secret is not set (env JWT_SECRET). This service will not "
							+ "start without a signing secret -- see application-local.yml for a dev-only example.");
		}
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
		return NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

	/**
	 * Requirement 2: "roles" claim -> ROLE_<name> authorities. A role name nothing checks for
	 * (a typo, a role from a future release) becomes an authority that simply matches no
	 * @PreAuthorize expression -- ignored, not an error. Nothing here validates the claim against
	 * a known role list.
	 */
	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
		authoritiesConverter.setAuthoritiesClaimName("roles");
		authoritiesConverter.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
		return converter;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
			KnownStaffAuthorizationManager knownStaffAuthorizationManager,
			ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
			ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception {
		http
			// Bearer-token API: no cookies are ever involved, so there is no session for a forged
			// cross-site request to ride on. CSRF protection defends cookie-based auth and does not
			// apply here.
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				// Requirement 3 (deny by default) + requirement 4 (known staff only, centrally):
				// every other route needs a valid, signed token AND a sub that is a real member of
				// staff. Per-route ROLE checks then live only in @PreAuthorize on each handler.
				.anyRequest().access(knownStaffAuthorizationManager))
			// oauth2ResourceServer() registers its own default entry point/handler (the
			// WWW-Authenticate-header, empty-body kind) scoped to bearer-token requests, which
			// otherwise takes precedence over the plain exceptionHandling() ones below -- set here
			// too so every 401/403 gets the same ProblemDetail body (requirement 7).
			.oauth2ResourceServer(oauth2 -> oauth2
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
				.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler));
		return http.build();
	}

}

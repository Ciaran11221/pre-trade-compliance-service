package io.github.ciaran11221.compliance.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler method as intentionally public. Every handler in this service must
 * carry either {@code @PreAuthorize} or this marker, so a handler can never go unguarded just
 * because nobody wrote an annotation on it -- see RouteAccessMatrixTest's Test C.
 *
 * No route currently uses it: /actuator/health is Boot's own endpoint, outside our controllers
 * entirely. The marker exists so a future public route has to say so on purpose.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {
}

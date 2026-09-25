package io.github.ciaran11221.compliance.security;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Pulls (HTTP method, path) -> handler java.lang.reflect.Method out of Spring's
 * RequestMappingHandlerMapping, filtered down to OUR OWN controllers by package prefix. This is
 * how Test A/Test C exclude Spring's own handlers without an explicit exclude-list:
 *
 * - Boot's error handler (BasicErrorController, mapped to /error) lives in a
 *   org.springframework.boot.* package, so the prefix filter drops it.
 * - Actuator endpoints (/actuator/**) are never registered on RequestMappingHandlerMapping at
 *   all -- Boot wires them through its own, separate WebMvcEndpointHandlerMapping bean -- so they
 *   never appear here regardless of filtering.
 */
public final class ControllerRoutes {

	private static final String OUR_PACKAGE_PREFIX = "io.github.ciaran11221.compliance";

	private ControllerRoutes() {
	}

	public static Map<Route, Method> from(RequestMappingHandlerMapping handlerMapping) {
		Map<Route, Method> routes = new LinkedHashMap<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handlerMethod = entry.getValue();
			if (!handlerMethod.getBeanType().getPackageName().startsWith(OUR_PACKAGE_PREFIX)) {
				continue;
			}
			RequestMappingInfo info = entry.getKey();
			Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
			Set<String> patterns = info.getPatternValues();
			for (RequestMethod method : methods) {
				for (String pattern : patterns) {
					routes.put(new Route(method.name(), pattern), handlerMethod.getMethod());
				}
			}
		}
		return routes;
	}

}

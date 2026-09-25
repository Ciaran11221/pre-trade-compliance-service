package io.github.ciaran11221.compliance.security;

/** An HTTP method + path pair, used to compare Spring's registered routes against the CSV. */
public record Route(String method, String path) {
}

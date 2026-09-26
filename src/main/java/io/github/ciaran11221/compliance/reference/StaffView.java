package io.github.ciaran11221.compliance.reference;

import java.time.Instant;

public record StaffView(String id, String name, String team, String role, Instant outOfOfficeFrom,
		Instant outOfOfficeUntil) {
}

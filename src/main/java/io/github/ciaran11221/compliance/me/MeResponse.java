package io.github.ciaran11221.compliance.me;

import java.util.List;

public record MeResponse(String staffId, String name, String team, List<String> roles, boolean outOfOffice) {
}

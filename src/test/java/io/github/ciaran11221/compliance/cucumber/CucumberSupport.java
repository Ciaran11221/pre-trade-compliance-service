package io.github.ciaran11221.compliance.cucumber;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TokenTool;

/**
 * Small stateless helpers shared by the step-definition classes in this package: signs a token
 * (same TEST_SECRET and TokenTool every existing HTTP-level test class uses), looks up a staff
 * member's own seeded role or a fund's numeric id, and resets the database/clock -- Trap #7's
 * "before each scenario and after the last one" reset, called from both Hooks (every scenario,
 * automatically) and FixtureSteps ("the demo data", so that sentence itself does something real).
 * A plain final class with static methods, not a Spring bean: it holds no scenario state (see
 * ScenarioState for that).
 */
final class CucumberSupport {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private CucumberSupport() {
	}

	static String roleOf(JdbcTemplate jdbcTemplate, String staffId) {
		String role = jdbcTemplate.queryForObject("SELECT role FROM staff WHERE id = ?", String.class, staffId);
		if (role == null) {
			throw new IllegalStateException("staff \"" + staffId + "\" has no seeded role");
		}
		return role;
	}

	static String token(String staffId, String role) {
		try {
			return TokenTool.signedToken(staffId, List.of(role), 5, TEST_SECRET);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	static long fundId(JdbcTemplate jdbcTemplate, String code) {
		Long id = jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = ?", Long.class, code);
		if (id == null) {
			throw new IllegalStateException("no fund with code \"" + code + "\"");
		}
		return id;
	}

	static void resetDatabase(Flyway flyway, MutableClock clock) {
		flyway.clean();
		flyway.migrate();
		clock.set(CucumberSpringConfiguration.FIXED_START);
	}

}

package io.github.ciaran11221.compliance.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps docs/SCENARIOS.md honest: it must equal what ScenarioIndex.render() produces from the
 * current corpus, right now, not what it produced the last time someone remembered to regenerate
 * it. Run with -Dscenarios.regenerate=true to write the file instead of asserting against it --
 * see CLAUDE.md's Commands table for the exact command.
 */
class ScenarioIndexTest {

	private static final Path INDEX_PATH = Path.of("docs/SCENARIOS.md");

	@Test
	void generatedIndexMatchesTheCommittedFile() throws IOException {
		List<Scenario> scenarios = ScenarioLoader.loadAllValidated();
		String rendered = ScenarioIndex.render(scenarios);

		if (Boolean.getBoolean("scenarios.regenerate")) {
			Files.writeString(INDEX_PATH, rendered, StandardCharsets.UTF_8);
			return;
		}

		String onDisk = Files.readString(INDEX_PATH, StandardCharsets.UTF_8);

		assertThat(rendered)
			.as("docs/SCENARIOS.md is stale. Regenerate it with: "
					+ "./mvnw test -Dtest=ScenarioIndexTest -Dscenarios.regenerate=true")
			.isEqualTo(onDisk);
	}

}

package io.github.ciaran11221.compliance.rules;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.ciaran11221.compliance.scenario.Scenario;
import io.github.ciaran11221.compliance.scenario.ScenarioLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build the moment a scenario file declares a kind with no runner to execute it.
 * RuleScenarioTest is the runner for "rule" today; adding a new kind to the corpus means adding
 * its runner and its name here in the same change.
 */
class ScenarioRunnerCoverageTest {

	private static final Set<String> KINDS_WITH_RUNNER = Set.of("rule", "limit-change", "quarantine");

	@Test
	void everyScenarioKindHasARunner() {
		Set<String> kinds = ScenarioLoader.loadAllValidated()
			.stream()
			.map(Scenario::kind)
			.collect(Collectors.toSet());

		Set<String> withoutRunner = new HashSet<>(kinds);
		withoutRunner.removeAll(KINDS_WITH_RUNNER);

		assertThat(withoutRunner).as("scenario kinds with no runner").isEmpty();
	}

}

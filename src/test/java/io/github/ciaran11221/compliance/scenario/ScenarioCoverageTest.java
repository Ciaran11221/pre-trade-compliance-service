package io.github.ciaran11221.compliance.scenario;

import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build if any RequiredCoverage provider names a tag no scenario covers. There is no
 * provider registered anywhere yet (no compliance rule exists in this milestone), so
 * ServiceLoader finds none, the required set is empty, and this passes trivially -- exactly as
 * it should until the first rule engine milestone adds one.
 */
class ScenarioCoverageTest {

	@Test
	void everyRequiredTagHasACoveringScenario() {
		Set<String> required = new LinkedHashSet<>();
		for (RequiredCoverage provider : ServiceLoader.load(RequiredCoverage.class)) {
			required.addAll(provider.requiredTags());
		}

		Set<String> covered = ScenarioLoader.loadAllValidated()
			.stream()
			.flatMap(scenario -> scenario.covers().stream())
			.collect(Collectors.toCollection(LinkedHashSet::new));

		ScenarioCoverageComparison comparison = ScenarioCoverageComparison.compare(required, covered);

		assertThat(comparison.matches()).as(comparison.describe()).isTrue();
	}

}

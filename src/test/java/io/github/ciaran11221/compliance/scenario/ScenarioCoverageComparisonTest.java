package io.github.ciaran11221.compliance.scenario;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meta-test: proves ScenarioCoverageTest actually bites. No ServiceLoader and no real corpus
 * here on purpose -- ScenarioCoverageComparison is a plain function of two sets, so a required
 * tag with no scenario can be shown to fail the comparison without a real RequiredCoverage
 * provider existing anywhere in the codebase.
 */
class ScenarioCoverageComparisonTest {

	@Test
	void matchesWhenEveryRequiredTagIsCovered() {
		Set<String> tags = Set.of("rule:diversification", "rule:cash");

		assertThat(ScenarioCoverageComparison.compare(tags, tags).matches()).isTrue();
	}

	@Test
	void aRequiredTagWithNoScenarioFailsTheMatch() {
		Set<String> required = Set.of("rule:diversification", "rule:does-not-exist");
		Set<String> covered = Set.of("rule:diversification");

		ScenarioCoverageComparison result = ScenarioCoverageComparison.compare(required, covered);

		assertThat(result.matches()).isFalse();
		assertThat(result.missing()).containsExactly("rule:does-not-exist");
		assertThat(result.describe()).contains("rule:does-not-exist");
	}

}

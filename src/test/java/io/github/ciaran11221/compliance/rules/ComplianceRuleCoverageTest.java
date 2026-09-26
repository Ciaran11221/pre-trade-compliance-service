package io.github.ciaran11221.compliance.rules;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.ciaran11221.compliance.scenario.ScenarioCoverageComparison;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves ComplianceRuleCoverage actually bites, without leaving a real uncovered rule in the
 * codebase for ScenarioCoverageTest to permanently trip over: it scans the real four rules, then
 * uses ScenarioCoverageComparison (the same helper ScenarioCoverageTest itself uses) against a
 * covered set missing one tag, and checks the comparison names it.
 */
class ComplianceRuleCoverageTest {

	@Test
	void findsEveryRegisteredRuleAsARequiredTag() {
		Set<String> required = new ComplianceRuleCoverage().requiredTags();

		assertThat(required).containsExactlyInAnyOrder("rule:restricted-list", "rule:cash", "rule:order-size",
				"rule:diversification", "rule:holding");
	}

	@Test
	void aRuleWithNoCoveringScenarioFailsTheComparisonByName() {
		Set<String> required = new ComplianceRuleCoverage().requiredTags();
		Set<String> covered = new LinkedHashSet<>(required);
		covered.remove("rule:diversification");

		ScenarioCoverageComparison comparison = ScenarioCoverageComparison.compare(required, covered);

		assertThat(comparison.matches()).isFalse();
		assertThat(comparison.missing()).containsExactly("rule:diversification");
		assertThat(comparison.describe()).contains("rule:diversification");
	}

}

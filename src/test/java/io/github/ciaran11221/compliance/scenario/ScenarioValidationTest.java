package io.github.ciaran11221.compliance.scenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ciaran11221.compliance.scenario.ScenarioLoader.Loaded;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves ScenarioValidation actually catches each rule it claims to, on synthetic scenarios built
 * in memory; no real corpus file is ever broken to prove this, same reasoning as
 * RouteSetComparisonTest not leaving an unguarded controller in the codebase.
 */
class ScenarioValidationTest {

	private static final Scenario.Given GIVEN = new Scenario.Given(Map.of(),
			new Scenario.Fund("HGF", new BigDecimal("1000000000"), new BigDecimal("100000000"), true), List.of(),
			List.of(), List.of(), List.of(), List.of());

	private static final Scenario.When WHEN = new Scenario.When(new Scenario.Order("BUY", "KSTL", 1000));

	@Test
	void aValidScenarioProducesNoErrors() {
		Scenario scenario = scenario("S001", "rule", List.of("rule:diversification"), "PASS");

		assertThat(ScenarioValidation.validate(List.of(new Loaded("S001-x.yaml", scenario)))).isEmpty();
	}

	@Test
	void missingIdIsReported() {
		Scenario scenario = new Scenario(null, "t", "rule", List.of("rule:diversification"), GIVEN, WHEN,
				new Scenario.Then("PASS", Map.of(), "n"), List.of());

		List<String> errors = ScenarioValidation.validate(List.of(new Loaded("S001-x.yaml", scenario)));

		assertThat(errors).anyMatch(e -> e.contains("S001-x.yaml") && e.contains("missing id"));
	}

	@Test
	void duplicateIdIsReportedNamingBothFiles() {
		Scenario scenario = scenario("S001", "rule", List.of("rule:diversification"), "PASS");

		List<String> errors = ScenarioValidation
			.validate(List.of(new Loaded("S001-a.yaml", scenario), new Loaded("S001-b.yaml", scenario)));

		assertThat(errors).anyMatch(e -> e.contains("duplicate id \"S001\"") && e.contains("S001-a.yaml")
				&& e.contains("S001-b.yaml"));
	}

	@Test
	void fileNameNotStartingWithTheIdIsReported() {
		Scenario scenario = scenario("S001", "rule", List.of("rule:diversification"), "PASS");

		List<String> errors = ScenarioValidation.validate(List.of(new Loaded("wrong-name.yaml", scenario)));

		assertThat(errors).anyMatch(e -> e.contains("wrong-name.yaml") && e.contains("does not start with \"S001-\""));
	}

	@Test
	void invalidKindIsReported() {
		Scenario scenario = scenario("S001", "not-a-kind", List.of("rule:diversification"), "PASS");

		List<String> errors = ScenarioValidation.validate(List.of(new Loaded("S001-x.yaml", scenario)));

		assertThat(errors).anyMatch(e -> e.contains("S001-x.yaml") && e.contains("kind must be one of"));
	}

	@Test
	void emptyCoversIsReported() {
		Scenario scenario = scenario("S001", "rule", List.of(), "PASS");

		List<String> errors = ScenarioValidation.validate(List.of(new Loaded("S001-x.yaml", scenario)));

		assertThat(errors).anyMatch(e -> e.contains("S001-x.yaml") && e.contains("covers must not be empty"));
	}

	@Test
	void aCoversTagNotOfTheFormAreaColonNameIsReported() {
		Scenario scenario = scenario("S001", "rule", List.of("diversification"), "PASS");

		List<String> errors = ScenarioValidation.validate(List.of(new Loaded("S001-x.yaml", scenario)));

		assertThat(errors).anyMatch(e -> e.contains("S001-x.yaml") && e.contains("not of the form <area>:<name>"));
	}

	@Test
	void missingOutcomeIsReported() {
		Scenario scenario = new Scenario("S001", "t", "rule", List.of("rule:diversification"), GIVEN, WHEN,
				new Scenario.Then(null, Map.of(), "n"), List.of());

		List<String> errors = ScenarioValidation.validate(List.of(new Loaded("S001-x.yaml", scenario)));

		assertThat(errors).anyMatch(e -> e.contains("S001-x.yaml") && e.contains("then.outcome is missing"));
	}

	private static Scenario scenario(String id, String kind, List<String> covers, String outcome) {
		return new Scenario(id, "t", kind, covers, GIVEN, WHEN, new Scenario.Then(outcome, Map.of(), "n"), List.of());
	}

}

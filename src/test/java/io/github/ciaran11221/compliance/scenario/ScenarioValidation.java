package io.github.ciaran11221.compliance.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.ciaran11221.compliance.scenario.ScenarioLoader.Loaded;

/**
 * Rules a scenario file must satisfy, checked across the whole corpus so a duplicate id can be
 * caught (a single file can't tell that on its own). Every error message names the file it came
 * from, since that is what a person or a build log needs to go fix it.
 */
public final class ScenarioValidation {

	static final Set<String> VALID_KINDS = Set.of("rule", "quarantine", "limit-change");

	static final Set<String> VALID_COVERS_AREAS = Set.of("rule", "quarantine", "limit-change", "security");

	private static final Pattern COVERS_TAG = Pattern.compile("(rule|quarantine|limit-change|security):.+");

	private ScenarioValidation() {
	}

	public static List<String> validate(List<Loaded> loaded) {
		List<String> errors = new ArrayList<>();
		Map<String, List<String>> filesById = new LinkedHashMap<>();

		for (Loaded entry : loaded) {
			String file = entry.fileName();
			Scenario scenario = entry.scenario();

			if (scenario.id() == null || scenario.id().isBlank()) {
				errors.add(file + ": missing id");
			}
			else {
				filesById.computeIfAbsent(scenario.id(), id -> new ArrayList<>()).add(file);
				if (!file.startsWith(scenario.id() + "-")) {
					errors.add(file + ": file name does not start with \"" + scenario.id() + "-\"");
				}
			}

			if (scenario.kind() == null || !VALID_KINDS.contains(scenario.kind())) {
				errors.add(file + ": kind must be one of " + VALID_KINDS + ", was \"" + scenario.kind() + "\"");
			}

			if (scenario.covers() == null || scenario.covers().isEmpty()) {
				errors.add(file + ": covers must not be empty");
			}
			else {
				for (String tag : scenario.covers()) {
					if (tag == null || !COVERS_TAG.matcher(tag).matches()) {
						errors.add(file + ": covers tag \"" + tag + "\" is not of the form <area>:<name> with area in "
								+ VALID_COVERS_AREAS);
					}
				}
			}

			if (scenario.then() == null || scenario.then().outcome() == null || scenario.then().outcome().isBlank()) {
				errors.add(file + ": then.outcome is missing");
			}

			if ("rule".equals(scenario.kind())) {
				errors.addAll(validateGivenOrFixture(file, scenario));
			}
		}

		filesById.forEach((id, files) -> {
			if (files.size() > 1) {
				errors.add("duplicate id \"" + id + "\" used by: " + files);
			}
		});

		return errors;
	}

	/**
	 * A "kind: rule" scenario sets exactly one of given (inline) or fixture (a name resolved
	 * against fixtures/fund-state/&lt;name&gt;.yaml by FixtureLoader) -- never both, never neither.
	 */
	private static List<String> validateGivenOrFixture(String file, Scenario scenario) {
		List<String> errors = new ArrayList<>();
		boolean hasGiven = scenario.given() != null;
		boolean hasFixture = scenario.fixture() != null && !scenario.fixture().isBlank();

		if (hasGiven && hasFixture) {
			errors.add(file + ": kind \"rule\" sets both given and fixture \"" + scenario.fixture()
					+ "\" -- exactly one is allowed");
		}
		else if (!hasGiven && !hasFixture) {
			errors.add(file + ": kind \"rule\" sets neither given nor fixture -- exactly one is required");
		}
		else if (hasFixture && !FixtureLoader.exists(scenario.fixture())) {
			errors.add(file + ": fixture \"" + scenario.fixture()
					+ "\" does not exist at fixtures/fund-state/" + scenario.fixture() + ".yaml");
		}
		return errors;
	}

}

package io.github.ciaran11221.compliance.scenario;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads one fund-state fixture from src/test/resources/fixtures/fund-state/&lt;name&gt;.yaml into
 * the same Scenario.Given shape a "kind: rule" scenario's inline given: block parses into -- the
 * ONE fixture shape M10 (issue #31) asks for, read identically by ScenarioLoader (for the JUnit
 * "fixture:" scenarios, S001-S005) and by the Cucumber fixture step
 * (io.github.ciaran11221.compliance.cucumber.FixtureSteps), so a new scenario needs a fixture file
 * plus Gherkin or YAML lines, never new Java.
 */
public final class FixtureLoader {

	private static final String LOCATION = "fixtures/fund-state/%s.yaml";

	private static final YAMLMapper YAML = YAMLMapper.builder().build();

	private FixtureLoader() {
	}

	public static boolean exists(String name) {
		return resource(name).exists();
	}

	/** Throws IllegalStateException, naming the file, if it does not exist or fails to parse. */
	public static Scenario.Given load(String name) {
		ClassPathResource resource = resource(name);
		if (!resource.exists()) {
			throw new IllegalStateException(
					"no fixture named \"" + name + "\": " + resource.getPath() + " does not exist");
		}
		try (InputStream in = resource.getInputStream()) {
			return YAML.readValue(in, Scenario.Given.class);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read fixture " + resource.getPath(), ex);
		}
	}

	private static ClassPathResource resource(String name) {
		return new ClassPathResource(LOCATION.formatted(name));
	}

}

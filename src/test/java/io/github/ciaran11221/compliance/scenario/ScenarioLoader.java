package io.github.ciaran11221.compliance.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads every scenario in src/test/resources/scenarios/ and validates the corpus as a whole.
 * Uses Spring's PathMatchingResourcePatternResolver rather than a plain File walk, so this works
 * the same whether the test runs from Maven, an IDE, or (later) a packaged jar -- classpath
 * globbing for "scenarios/*.yaml" is exactly the case it exists for.
 */
public final class ScenarioLoader {

	private static final String LOCATION_PATTERN = "classpath*:scenarios/*.yaml";

	private static final YAMLMapper YAML = YAMLMapper.builder().build();

	private ScenarioLoader() {
	}

	/** A parsed scenario together with the file name it came from, for messages naming the file. */
	public record Loaded(String fileName, Scenario scenario) {
	}

	/** Parses every scenario file. Does not validate; see loadAllValidated(). */
	public static List<Loaded> loadAll() {
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		Resource[] resources;
		try {
			resources = resolver.getResources(LOCATION_PATTERN);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not list " + LOCATION_PATTERN, ex);
		}

		List<Loaded> loaded = new ArrayList<>();
		for (Resource resource : resources) {
			String fileName = resource.getFilename();
			try (InputStream in = resource.getInputStream()) {
				Scenario scenario = YAML.readValue(in, Scenario.class);
				loaded.add(new Loaded(fileName, scenario));
			}
			catch (IOException ex) {
				throw new IllegalStateException("Could not read scenario file " + fileName, ex);
			}
		}
		loaded.sort(Comparator.comparing(Loaded::fileName));
		return loaded;
	}

	/**
	 * Parses every scenario file and validates the corpus, sorted by id. Throws, naming every
	 * problem file, if validation fails -- see ScenarioValidation for the rules.
	 */
	public static List<Scenario> loadAllValidated() {
		List<Loaded> loaded = loadAll();
		List<String> errors = ScenarioValidation.validate(loaded);
		if (!errors.isEmpty()) {
			throw new IllegalStateException("Scenario corpus is invalid:\n" + String.join("\n", errors));
		}
		return loaded.stream().map(Loaded::scenario).sorted(Comparator.comparing(Scenario::id)).toList();
	}

}

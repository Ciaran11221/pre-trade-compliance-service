package io.github.ciaran11221.compliance.scenario;

import java.util.Set;

/**
 * A source of tags that some scenario must cover. Providers are test code: each one lists the
 * things that need a scenario (for example every compliance rule, as "rule:<name>"), and is
 * registered in src/test/resources/META-INF/services/ under this interface's full name.
 *
 * ServiceLoader rather than a Spring context: computing a set of strings needs no application
 * context, and a new provider plugs in with one class and one line in the service file, with no
 * change to this interface, ScenarioCoverageTest or ScenarioCoverageComparison.
 */
public interface RequiredCoverage {

	Set<String> requiredTags();

}

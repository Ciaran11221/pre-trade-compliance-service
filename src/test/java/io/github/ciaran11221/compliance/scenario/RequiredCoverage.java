package io.github.ciaran11221.compliance.scenario;

import java.util.Set;

/**
 * A source of tags that some scenario must cover. There is no implementation yet: no compliance
 * rule exists in this milestone, so nothing is required. A later milestone that adds a rule
 * engine also adds one production class implementing this (for example a class listing
 * "rule:diversification", "rule:cash", ...), registered as a provider under
 * src/main/resources/META-INF/services/io.github.ciaran11221.compliance.scenario.RequiredCoverage
 * naming that class.
 *
 * ServiceLoader over a plain static list: no Spring context is needed to compute a set of
 * strings, and ServiceLoader lets a later provider plug in by adding one class plus one service
 * file, with no change to this interface, ScenarioCoverageTest, or ScenarioCoverageComparison.
 */
public interface RequiredCoverage {

	Set<String> requiredTags();

}

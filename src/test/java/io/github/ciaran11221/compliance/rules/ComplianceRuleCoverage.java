package io.github.ciaran11221.compliance.rules;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import io.github.ciaran11221.compliance.scenario.RequiredCoverage;

/**
 * Finds every ComplianceRule implementation under this package by classpath scanning, instantiates
 * each with its public no-arg constructor, and requires a scenario tagged "rule:<name()>" for
 * every one it finds. A new rule with no scenario fails ScenarioCoverageTest by name the moment it
 * is added, with nothing else to remember to wire up.
 */
public class ComplianceRuleCoverage implements RequiredCoverage {

	private static final String RULES_PACKAGE = "io.github.ciaran11221.compliance.rules";

	@Override
	public Set<String> requiredTags() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(ComplianceRule.class));

		Set<String> tags = new LinkedHashSet<>();
		for (BeanDefinition beanDefinition : scanner.findCandidateComponents(RULES_PACKAGE)) {
			tags.add("rule:" + instantiate(beanDefinition).name());
		}
		return tags;
	}

	private static ComplianceRule instantiate(BeanDefinition beanDefinition) {
		try {
			Class<?> ruleClass = Class.forName(beanDefinition.getBeanClassName());
			return (ComplianceRule) ruleClass.getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Could not instantiate " + beanDefinition.getBeanClassName(), ex);
		}
	}

}

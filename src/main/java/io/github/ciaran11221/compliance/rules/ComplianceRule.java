package io.github.ciaran11221.compliance.rules;

/**
 * One pre-trade check. Implementations are pure: no Spring dependency beyond the @Component
 * annotation that lets the engine collect them, no repository call, no field but a public no-arg
 * constructor. Everything a rule needs to decide arrives in the OrderContext passed to evaluate.
 */
public interface ComplianceRule {

	/**
	 * Kebab-case, matching this rule's corpus tag without the "rule:" prefix (for example
	 * "restricted-list" for the tag "rule:restricted-list").
	 */
	String name();

	RuleResult evaluate(OrderContext context);

}

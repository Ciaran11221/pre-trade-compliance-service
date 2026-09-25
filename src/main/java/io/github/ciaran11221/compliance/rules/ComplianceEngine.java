package io.github.ciaran11221.compliance.rules;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Runs every injected ComplianceRule against one order and combines their results: BLOCK if any
 * rule blocked, else REVIEW if any rule sent it to review, else PASS. Results come back sorted by
 * rule name so the order is stable regardless of Spring's bean injection order.
 */
@Component
public class ComplianceEngine {

	private final List<ComplianceRule> rules;

	public ComplianceEngine(List<ComplianceRule> rules) {
		this.rules = rules;
	}

	public EngineResult evaluate(OrderContext context) {
		List<RuleResult> results = rules.stream()
			.map(rule -> rule.evaluate(context))
			.sorted(Comparator.comparing(RuleResult::ruleName))
			.toList();

		return new EngineResult(combine(results), results);
	}

	private static RuleOutcome combine(List<RuleResult> results) {
		if (results.stream().anyMatch(result -> result.outcome() == RuleOutcome.BLOCK)) {
			return RuleOutcome.BLOCK;
		}
		if (results.stream().anyMatch(result -> result.outcome() == RuleOutcome.REVIEW)) {
			return RuleOutcome.REVIEW;
		}
		return RuleOutcome.PASS;
	}

	public record EngineResult(RuleOutcome outcome, List<RuleResult> results) {
	}

}

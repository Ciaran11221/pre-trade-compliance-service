package io.github.ciaran11221.compliance.limits;

import java.util.Set;

import io.github.ciaran11221.compliance.scenario.RequiredCoverage;

/**
 * The ten limit-change behaviours the scenario corpus must cover, one scenario per tag: every hard
 * rule (a legal maximum, self-approval, rank not bypassing the approval count, cross-team, cooling
 * off, a plain tighten, the approval-threshold settings themselves, a hidden breach, staleness, and
 * an out-of-office approver). Unlike ComplianceRuleCoverage this list is hand-written, not derived
 * by scanning classes: there is no one class per rule to scan for, only this fixed rule set.
 */
public class LimitChangeCoverage implements RequiredCoverage {

	@Override
	public Set<String> requiredTags() {
		return Set.of("limit-change:legal-maximum", "limit-change:self-approval", "limit-change:no-rank-bypass",
				"limit-change:different-team", "limit-change:cooling-off", "limit-change:tightening",
				"limit-change:threshold-back-door", "limit-change:hides-breach", "limit-change:stale",
				"limit-change:out-of-office-approver");
	}

}

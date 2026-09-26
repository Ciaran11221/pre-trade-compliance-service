package io.github.ciaran11221.compliance.quarantine;

import java.util.Set;

import io.github.ciaran11221.compliance.scenario.RequiredCoverage;

/**
 * One tag per rule issue #14 lists under "Done when" (see the milestone prompt), so a rule with no
 * scenario fails ScenarioCoverageTest by name. Unlike ComplianceRuleCoverage, this is a fixed list
 * rather than classpath-scanned: quarantine's rules are not one-class-per-rule the way
 * ComplianceRule is, so there is nothing to scan.
 */
public class QuarantineCoverage implements RequiredCoverage {

	@Override
	public Set<String> requiredTags() {
		return Set.of("quarantine:possible-duplicate", "quarantine:opposite-side",
				"quarantine:sender-out-of-office", "quarantine:self-release", "quarantine:out-of-office-releaser",
				"quarantine:escalate-to-backup", "quarantine:escalate-to-compliance", "quarantine:unassigned",
				"quarantine:expiry-without-job", "quarantine:counts-as-pending");
	}

}

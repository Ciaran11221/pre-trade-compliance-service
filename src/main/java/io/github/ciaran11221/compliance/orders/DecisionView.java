package io.github.ciaran11221.compliance.orders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * settingsSnapshot and inputsSnapshot are exactly what was read from setting_value/decision.
 * inputs_snapshot at decision time -- read back verbatim, never recomputed from the fund's current
 * state -- so a decision stays explainable even after limits or holdings later change. See
 * OrderService.settingsSnapshot/inputsSnapshot for what goes into them.
 */
public record DecisionView(String outcome, Instant decidedAt, List<RuleResultView> ruleResults,
		Map<String, Object> settingsSnapshot, Map<String, Object> inputsSnapshot) {
}

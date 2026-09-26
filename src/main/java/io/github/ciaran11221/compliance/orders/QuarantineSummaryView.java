package io.github.ciaran11221.compliance.orders;

import java.time.Instant;

/**
 * The quarantine facts an order's own view carries (issue #14): why it was quarantined, the
 * earlier order it matched (null for SENDER_OUT_OF_OFFICE), its escalation state and who it is
 * assigned to, and its timing. Release/reject detail (who resolved it and when) lives in the
 * quarantine package's own views, reached through GET /api/quarantine, not here.
 *
 * <p>
 * assignment (issue #27) is one of ANY_SUPERVISOR (an eligible releaser was in office; assignedTo
 * is null because the hold is open to any supervisor, not because nobody was available),
 * ASSIGNED (assignedTo names the backup or COMPLIANCE user it escalated to), UNASSIGNED (nobody
 * eligible was in office; assignedTo is null because there is nobody to name, and the hold is left
 * to expire), or NOT_RECORDED for a quarantine written before this field existed -- never guessed
 * from assignedTo alone, which is exactly the ambiguity issue #27 reports. assignedTo alone can no
 * longer answer "is this open to anyone, or to nobody at all": always read assignment for that.
 */
public record QuarantineSummaryView(String reason, Long matchedOrderId, String assignment, String assignedTo,
		Instant quarantinedAt, Instant expiresAt) {
}

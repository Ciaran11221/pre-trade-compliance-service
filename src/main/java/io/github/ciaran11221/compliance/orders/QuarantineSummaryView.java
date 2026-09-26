package io.github.ciaran11221.compliance.orders;

import java.time.Instant;

/**
 * The quarantine facts an order's own view carries (issue #14): why it was quarantined, the
 * earlier order it matched (null for SENDER_OUT_OF_OFFICE), who it is assigned to (null if
 * unassigned), and its timing. Release/reject detail (who resolved it and when) lives in the
 * quarantine package's own views, reached through GET /api/quarantine, not here.
 */
public record QuarantineSummaryView(String reason, Long matchedOrderId, String assignedTo, Instant quarantinedAt,
		Instant expiresAt) {
}

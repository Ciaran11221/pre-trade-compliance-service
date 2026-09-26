package io.github.ciaran11221.compliance.quarantine;

import java.time.Instant;

/**
 * One row of GET /api/quarantine (spec 3.3/3.6): an open quarantine and the order it belongs to.
 * assignment (issue #27) is ANY_SUPERVISOR, ASSIGNED, UNASSIGNED or NOT_RECORDED -- see
 * orders.QuarantineSummaryView's Javadoc for what each means and why assignedTo alone cannot tell
 * ANY_SUPERVISOR and UNASSIGNED apart.
 */
public record QuarantineListItemView(long orderId, long fundId, String ticker, String side, long quantity,
		String submittedBy, String reason, Long matchedOrderId, Instant quarantinedAt, Instant expiresAt,
		String assignment, String assignedTo) {
}

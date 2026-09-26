package io.github.ciaran11221.compliance.quarantine;

import java.time.Instant;

/** One row of GET /api/quarantine (spec 3.3/3.6): an open quarantine and the order it belongs to. */
public record QuarantineListItemView(long orderId, long fundId, String ticker, String side, long quantity,
		String submittedBy, String reason, Long matchedOrderId, Instant quarantinedAt, Instant expiresAt,
		String assignedTo) {
}

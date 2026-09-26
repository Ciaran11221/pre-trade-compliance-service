package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * status is read from the order's latest order_event, never stored on the order itself: FILLED or
 * CANCELLED once that event exists, otherwise the outcome of its decision (PASS/REVIEW/BLOCK).
 * decision is present in every M7a order, since quarantine (which would leave an order with no
 * decision yet) does not exist until M7b -- see OrderService's SEAM comment.
 */
public record OrderView(long id, String clientOrderId, long fundId, String side, String ticker, long quantity,
		BigDecimal referencePrice, String submittedBy, Instant submittedAt, String status, DecisionView decision) {
}

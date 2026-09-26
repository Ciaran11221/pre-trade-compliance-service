package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * status is read from the order's latest order_event, never stored on the order itself: FILLED or
 * CANCELLED once that event exists, otherwise the outcome of its decision (PASS/REVIEW/BLOCK), or
 * -- since M7b, issue #14 -- QUARANTINED/RELEASED/REJECTED/EXPIRED while a quarantine governs it.
 * decision is null for an order that is currently quarantined and not yet released (quarantine
 * runs before the compliance engine, so there is nothing to report yet); quarantine is null for
 * every order that was never quarantined, and populated (reason, the earlier order it matched if
 * any, its assignee, when it was quarantined and when it expires) for one that was, whether or not
 * it has since been released or rejected.
 */
public record OrderView(long id, String clientOrderId, long fundId, String side, String ticker, long quantity,
		BigDecimal referencePrice, String submittedBy, Instant submittedAt, String status, DecisionView decision,
		QuarantineSummaryView quarantine) {
}

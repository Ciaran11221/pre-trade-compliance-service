package io.github.ciaran11221.compliance.limits;

/**
 * A limit-change request's status, derived every time it is asked for rather than stored: PENDING
 * (not yet approved enough to activate), AWAITING_ACTIVATION (approved, waiting for a cooling-off
 * period to elapse), ACTIVE (its activation's activates_at has passed), CANCELLED, or STALE (still
 * pending, but the key's active value has since moved away from this request's old_value, so
 * approving it would be approving a change that no longer starts where it thinks it does).
 */
public enum LimitChangeStatus {

	PENDING, AWAITING_ACTIVATION, ACTIVE, CANCELLED, STALE

}

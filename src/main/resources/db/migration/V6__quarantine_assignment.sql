-- Issue #27: quarantine.assigned_to = NULL was used for two different situations -- an eligible
-- SUPERVISOR is in office (the hold is open to any supervisor) and nobody eligible is in office at
-- all (the hold is left to expire) -- and a reader could not tell them apart. This column records
-- which of the two OrderService.resolveAssignee actually found, decided once, inside the same
-- transaction as the quarantine row itself and under the same fund lock as today; it is never
-- recomputed from current out-of-office flags on a later read.
--
-- Nullable in DDL on purpose: a row written before this migration recorded no such state, and
-- guessing one from assigned_to alone is exactly the ambiguity being fixed here. The read path
-- (OrderRepository/QuarantineRepository) maps a NULL assignment to NOT_RECORDED rather than ever
-- treating it as one of the three real states. quarantine is an insert-only audit table (trigger
-- from V2), so old rows are never backfilled -- there is nothing here but a new column and
-- constraints that bind future writes.
--
-- Every constraint below is added NOT VALID: Postgres then checks it on every INSERT and UPDATE
-- from this point on, but never scans the rows that already exist, so old rows are left exactly as
-- they are.
ALTER TABLE quarantine ADD COLUMN assignment VARCHAR(16) NULL;

ALTER TABLE quarantine ADD CONSTRAINT quarantine_assignment_known_value
    CHECK (assignment IS NULL OR assignment IN ('ANY_SUPERVISOR', 'ASSIGNED', 'UNASSIGNED')) NOT VALID;

ALTER TABLE quarantine ADD CONSTRAINT quarantine_assignment_required
    CHECK (assignment IS NOT NULL) NOT VALID;

-- ASSIGNED carries a named assignee and only ASSIGNED does: the two sides of this equality always
-- agree for a new row.
ALTER TABLE quarantine ADD CONSTRAINT quarantine_assignment_matches_assignee
    CHECK ((assignment = 'ASSIGNED') = (assigned_to IS NOT NULL)) NOT VALID;

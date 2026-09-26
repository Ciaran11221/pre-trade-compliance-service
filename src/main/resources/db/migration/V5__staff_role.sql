-- M7b (issue #14): escalation needs to find "every SUPERVISOR" and "every COMPLIANCE user" as a
-- fact about a staff member. Roles otherwise live only in a token's "roles" claim, which says
-- nothing about staff who are not the caller, and escalation runs inside order intake with no
-- second person's token in hand.
--
-- Nullable on purpose, with no backfill. A versioned migration runs once on every database,
-- including one with real staff, so it must not guess roles from demo ids. A staff member with
-- no role recorded is never chosen by escalation; a held order then falls through to COMPLIANCE
-- or "unassigned", which is visible. The demo roles come from R__seed.sql (local and test
-- profiles only). Permissions are unaffected: @PreAuthorize still reads the token's roles.

ALTER TABLE staff ADD COLUMN role VARCHAR(32);
ALTER TABLE staff ADD CONSTRAINT staff_role_check
    CHECK (role IS NULL OR role IN ('TRADER', 'SUPERVISOR', 'COMPLIANCE', 'EXECUTIVE'));

-- M7b (quarantine, issue #14): escalation needs to find "every SUPERVISOR" and "every COMPLIANCE
-- user" as a fact about a staff member, independent of any one login. Roles up to now have lived
-- only in a JWT's "roles" claim (see SecurityConfig.jwtAuthenticationConverter) -- a property of a
-- token, not of the person -- which has nothing to say about a staff member who is not the caller
-- of the current request. Escalation runs inside order intake, with no second person's token in
-- hand, so it needs a role recorded on the staff row itself. This is the minimal addition: one
-- column, backfilled from what R__seed.sql already implies about each seeded id (sup-* are
-- supervisors, comp-1 compliance, exec-1 executive, everyone else a trader), then locked down with
-- the same hard CHECK the token claims are implicitly limited to (spec 3.5's four roles).
--
-- The backfill runs before the NOT NULL/CHECK are added so it also covers a database that already
-- had these rows from an earlier R__seed.sql run (this migration predates the column, so on a
-- fresh database the table is still empty at this point and every UPDATE below is a no-op; R__seed
-- 's own INSERT statements supply role for every row from here on).

ALTER TABLE staff ADD COLUMN role VARCHAR(32);

UPDATE staff SET role = 'SUPERVISOR' WHERE id IN ('sup-1', 'sup-2', 'sup-3');
UPDATE staff SET role = 'COMPLIANCE' WHERE id = 'comp-1';
UPDATE staff SET role = 'EXECUTIVE' WHERE id = 'exec-1';
UPDATE staff SET role = 'TRADER' WHERE role IS NULL;

ALTER TABLE staff ALTER COLUMN role SET NOT NULL;
ALTER TABLE staff ADD CONSTRAINT staff_role_check CHECK (role IN ('TRADER', 'SUPERVISOR', 'COMPLIANCE', 'EXECUTIVE'));

-- Fictional demo data for local development and tests, loaded only by the
-- local and test profiles.
--
-- Repeatable (R__) rather than versioned: Flyway runs repeatable migrations
-- after every versioned one, so a new V-number schema migration never lands
-- "below" data that is already applied. A versioned seed (tried as V1000)
-- made Flyway refuse to start on any existing local database as soon as a V4
-- was added. Every insert is ON CONFLICT DO NOTHING so a re-run is harmless.

-- Securities. KSTL plus five more fictional tickers. voting_shares_outstanding
-- is set well above 10x every seeded holding below, so no holding gets near
-- the 10% voting-limit threshold from seed data alone.
INSERT INTO security (ticker, name, issuer_name, price, voting_shares_outstanding, avg_daily_volume) VALUES
    ('KSTL', 'Kestrel Materials Corp', 'Kestrel Materials Corp', 100.0000, 50000000, 2000000),
    ('NRTH', 'Northale Industries', 'Northale Industries', 150.0000, 40000000, 1500000),
    ('VLCN', 'Volcane Energy Group', 'Volcane Energy Group', 250.0000, 30000000, 1200000),
    ('TDRA', 'Tidewrack Logistics', 'Tidewrack Logistics', 200.0000, 45000000, 1800000),
    ('QSTN', 'Questane Semiconductor', 'Questane Semiconductor', 460.0000, 25000000, 900000),
    ('ZPHR', 'Zephyrine Holdings', 'Zephyrine Holdings', 50.0000, 20000000, 500000)
ON CONFLICT DO NOTHING;

-- One security on the restricted list.
INSERT INTO restricted_security (security_id, reason, added_at)
SELECT id, 'Pending litigation disclosure', '2026-01-15T00:00:00Z'
FROM security WHERE ticker = 'ZPHR'
ON CONFLICT DO NOTHING;

-- Fund HGF "Harbour Growth Fund".
-- total_assets 1,000,000,000.00, cash 100,000,000.00, diversified.
INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES
    ('HGF', 'Harbour Growth Fund', 1000000000.0000, 100000000.0000, true)
ON CONFLICT DO NOTHING;

-- HGF holdings:
--   KSTL   450,000 sh x $100.00 = $45,000,000  (worth $45M, as required)
--   NRTH   500,000 sh x $150.00 = $75,000,000  (> 5% of $1B = $50M)
--   VLCN   300,000 sh x $250.00 = $75,000,000  (> 5% of $1B = $50M)
--   NRTH + VLCN = $75,000,000 + $75,000,000 = $150,000,000 exactly, as required.
INSERT INTO holding (fund_id, security_id, quantity)
SELECT f.id, s.id, q.quantity
FROM (VALUES ('HGF', 'KSTL', 450000::bigint), ('HGF', 'NRTH', 500000::bigint), ('HGF', 'VLCN', 300000::bigint))
    AS q(fund_code, ticker, quantity)
JOIN fund f ON f.code = q.fund_code
JOIN security s ON s.ticker = q.ticker
ON CONFLICT DO NOTHING;

-- Fund WVF "Westshore Value Fund".
-- total_assets 1,000,000,000.00, cash 100,000,000.00, diversified.
INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES
    ('WVF', 'Westshore Value Fund', 1000000000.0000, 100000000.0000, true)
ON CONFLICT DO NOTHING;

-- WVF holdings:
--   KSTL   450,000 sh x $100.00 = $45,000,000  (worth $45M, as required)
--   TDRA   575,000 sh x $200.00 = $115,000,000  (> 5% of $1B = $50M)
--   QSTN   250,000 sh x $460.00 = $115,000,000  (> 5% of $1B = $50M)
--   TDRA + QSTN = $115,000,000 + $115,000,000 = $230,000,000 exactly, as required.
INSERT INTO holding (fund_id, security_id, quantity)
SELECT f.id, s.id, q.quantity
FROM (VALUES ('WVF', 'KSTL', 450000::bigint), ('WVF', 'TDRA', 575000::bigint), ('WVF', 'QSTN', 250000::bigint))
    AS q(fund_code, ticker, quantity)
JOIN fund f ON f.code = q.fund_code
JOIN security s ON s.ticker = q.ticker
ON CONFLICT DO NOTHING;

-- Fund CBF "Corrib Balanced Fund".
-- total_assets 1,000,000,000.00, diversified.
INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES
    ('CBF', 'Corrib Balanced Fund', 1000000000.0000, 100000000.0000, true)
ON CONFLICT DO NOTHING;

-- CBF holdings:
--   KSTL   600,000 sh x $100.00 = $60,000,000  (6% of $1B, as required)
INSERT INTO holding (fund_id, security_id, quantity)
SELECT f.id, s.id, 600000
FROM fund f, security s
WHERE f.code = 'CBF' AND s.ticker = 'KSTL'
ON CONFLICT DO NOTHING;

-- Staff. anne's backup is sup-2. Nobody is out of office.
-- sup-3 is a third desk-a supervisor, added for the limit-change "different-team" scenario (M6):
-- that scenario needs two DISTINCT approvers who share the requester's team, and desk-a otherwise
-- has only two members (anne and sup-1), one of whom would have to be the requester.
-- role (M7b, issue #14): see V5__staff_role.sql for why this lives on the staff row.
INSERT INTO staff (id, name, team, backup_staff_id, out_of_office_from, out_of_office_until, role) VALUES
    ('sup-1', 'Siobhan Nagle', 'desk-a', NULL, NULL, NULL, 'SUPERVISOR'),
    ('sup-2', 'Malachy Ferris', 'desk-b', NULL, NULL, NULL, 'SUPERVISOR'),
    ('sup-3', 'Fiachra Bellew', 'desk-a', NULL, NULL, NULL, 'SUPERVISOR'),
    ('comp-1', 'Orla Whitfield', 'compliance', NULL, NULL, NULL, 'COMPLIANCE'),
    ('exec-1', 'Declan Yorath', 'executive', NULL, NULL, NULL, 'EXECUTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO staff (id, name, team, backup_staff_id, out_of_office_from, out_of_office_until, role) VALUES
    ('anne', 'Anne Colquhoun', 'desk-a', 'sup-2', NULL, NULL, 'TRADER'),
    ('brian', 'Brian Meath', 'desk-b', NULL, NULL, NULL, 'TRADER')
ON CONFLICT DO NOTHING;

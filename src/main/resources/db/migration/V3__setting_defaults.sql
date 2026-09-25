-- Firm limits, as they stand from day one. Real config, not seed/demo data,
-- so it lives in the versioned migration path and loads in every environment.
-- No change_request_id: these defaults were never approved through the
-- change-request process, they are the starting point it works from.

INSERT INTO setting_value (setting_key, value, active_from, change_request_id) VALUES
    ('ISSUER_LIMIT_PCT', 5, '1970-01-01T00:00:00Z', NULL),
    ('VOTING_LIMIT_PCT', 10, '1970-01-01T00:00:00Z', NULL),
    ('OVER_LIMIT_BUCKET_PCT', 25, '1970-01-01T00:00:00Z', NULL),
    ('ORDER_SIZE_ADV_PCT', 10, '1970-01-01T00:00:00Z', NULL),
    ('LOOKBACK_MINUTES', 5, '1970-01-01T00:00:00Z', NULL),
    ('SIMILARITY_PCT', 10, '1970-01-01T00:00:00Z', NULL),
    ('QUARANTINE_EXPIRY_MINUTES', 30, '1970-01-01T00:00:00Z', NULL),
    ('LARGE_LOOSENING_USD', 50000000, '1970-01-01T00:00:00Z', NULL),
    ('LARGE_LOOSENING_PCT_OF_FUNDS', 1, '1970-01-01T00:00:00Z', NULL),
    ('COOLING_OFF_HOURS', 24, '1970-01-01T00:00:00Z', NULL);

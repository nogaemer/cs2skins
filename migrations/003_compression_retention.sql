-- ============================================================
-- Migration 003: Compression and Retention Policies
-- ============================================================
-- Enables TimescaleDB native compression on the two hypertables
-- and registers background policies that compress old chunks and
-- drop data beyond the retention window.
--
-- Prerequisites: migrations 001 and 002 must have been applied
-- (tables must exist as TimescaleDB hypertables with timestamptz
-- time-dimension columns).
--
-- All policy calls use if_not_exists => TRUE so this migration is
-- safe to re-run (idempotent).
--
-- ┌────────────────────────────┬──────────────┬──────────────────┐
-- │ Table                      │ Compress after│ Retain raw data  │
-- ├────────────────────────────┼──────────────┼──────────────────┤
-- │ skin_price_history         │    7 days    │     90 days      │
-- │ tradeup_snapshots          │    7 days    │     90 days      │
-- └────────────────────────────┴──────────────┴──────────────────┘
--
-- HOW TO CHANGE THESE VALUES AFTER DEPLOYMENT
-- ─────────────────────────────────────────────
-- Use the TimescaleDB functions below (no migration needed):
--
--   -- Change compression schedule (e.g., compress after 14 days):
--   SELECT alter_compression_policy('skin_price_history',
--            compress_after => INTERVAL '14 days');
--   SELECT alter_compression_policy('tradeup_snapshots',
--            compress_after => INTERVAL '14 days');
--
--   -- Change retention window (e.g., keep 180 days):
--   SELECT alter_retention_policy('skin_price_history',
--            drop_after => INTERVAL '180 days');
--   SELECT alter_retention_policy('tradeup_snapshots',
--            drop_after => INTERVAL '180 days');
--
--   -- Remove a policy entirely:
--   SELECT remove_compression_policy('skin_price_history');
--   SELECT remove_retention_policy('skin_price_history');
--
-- ============================================================

-- ============================================================
-- skin_price_history
-- ============================================================

-- Enable compression.
--   compress_segmentby: group chunks by skin_id + wear_id so that
--     queries filtering on those columns can skip decompression of
--     unrelated segments.
--   compress_orderby: rows within each segment are sorted by
--     recorded_at DESC, matching the typical "latest first" query
--     pattern and maximising delta-compression efficiency.
ALTER TABLE skin_price_history
    SET (
        timescaledb.compress,
        timescaledb.compress_segmentby = 'skin_id, wear_id',
        timescaledb.compress_orderby   = 'recorded_at DESC'
    );

-- Schedule automatic compression: compress chunks older than 7 days.
-- Background job runs approximately once per day.
-- To tune: SELECT alter_compression_policy('skin_price_history', compress_after => INTERVAL 'X days');
SELECT add_compression_policy(
    'skin_price_history',
    compress_after => INTERVAL '7 days',
    if_not_exists  => TRUE
);

-- Schedule automatic retention: drop chunks older than 90 days.
-- The retention window must be >= the compression window (7 days here).
-- To tune: SELECT alter_retention_policy('skin_price_history', drop_after => INTERVAL 'X days');
SELECT add_retention_policy(
    'skin_price_history',
    drop_after    => INTERVAL '90 days',
    if_not_exists => TRUE
);

-- ============================================================
-- tradeup_snapshots
-- ============================================================

-- Enable compression.
--   compress_segmentby: group by tradeup_id so that per-trade-up
--     time-range queries can skip unrelated segments.
--   compress_orderby: ordered by snapshot_time DESC for optimal
--     delta compression of the metric columns.
ALTER TABLE tradeup_snapshots
    SET (
        timescaledb.compress,
        timescaledb.compress_segmentby = 'tradeup_id',
        timescaledb.compress_orderby   = 'snapshot_time DESC'
    );

-- Schedule automatic compression: compress chunks older than 7 days.
-- To tune: SELECT alter_compression_policy('tradeup_snapshots', compress_after => INTERVAL 'X days');
SELECT add_compression_policy(
    'tradeup_snapshots',
    compress_after => INTERVAL '7 days',
    if_not_exists  => TRUE
);

-- Schedule automatic retention: drop chunks older than 90 days.
-- To tune: SELECT alter_retention_policy('tradeup_snapshots', drop_after => INTERVAL 'X days');
SELECT add_retention_policy(
    'tradeup_snapshots',
    drop_after    => INTERVAL '90 days',
    if_not_exists => TRUE
);

-- ============================================================
-- Verify: confirm policies are registered.
-- Returns one row per policy; missing rows indicate a failure.
-- ============================================================
SELECT hypertable_name,
       compression_enabled
FROM   timescaledb_information.hypertables
WHERE  hypertable_name IN ('skin_price_history', 'tradeup_snapshots')
ORDER  BY hypertable_name;

SELECT hypertable_name,
       schedule_interval,
       config
FROM   timescaledb_information.jobs
WHERE  proc_name IN ('policy_compression', 'policy_retention')
  AND  hypertable_name IN ('skin_price_history', 'tradeup_snapshots')
ORDER  BY hypertable_name, proc_name;

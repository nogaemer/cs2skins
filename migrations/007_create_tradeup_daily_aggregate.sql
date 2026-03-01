-- ============================================================
-- Migration 007: tradeup_daily Continuous Aggregate
-- ============================================================
-- Creates a TimescaleDB continuous aggregate (materialized view)
-- named tradeup_daily that pre-computes daily average ROI,
-- average profit, average input/output costs, and sample count
-- per trade-up from tradeup_snapshots.
--
-- This aggregate is used by:
--   GET /api/tradeups/{id}/history  (when bucket >= 1 day)
--   GET /api/tradeups/top           (when bucket=day)
--
-- When to use the aggregate vs. raw data:
--   • bucket >= 1 day  → read from tradeup_daily (fast, indexed)
--   • bucket < 1 day   → fall back to raw tradeup_snapshots
--
-- The refresh policy re-materialises data once per hour,
-- covering a window from 3 days ago up to 1 hour ago.  This
-- means the most recent ~1 hour of snapshots is always read
-- from the raw hypertable.
--
-- Prerequisites: migrations 001–006 must have been applied.
-- ============================================================

-- ── Continuous aggregate ──────────────────────────────────────
CREATE MATERIALIZED VIEW IF NOT EXISTS tradeup_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', snapshot_time) AS bucket,
    tradeup_id,
    AVG(roi)         AS avg_roi,
    AVG(profit)      AS avg_profit,
    AVG(input_cost)  AS avg_input_cost,
    AVG(output_cost) AS avg_output_cost,
    COUNT(*)         AS samples
FROM tradeup_snapshots
GROUP BY bucket, tradeup_id
WITH NO DATA;

-- ── Refresh policy ────────────────────────────────────────────
-- Materialises buckets that are between 3 days old and 1 hour
-- old.  Runs every hour in the background.
SELECT add_continuous_aggregate_policy(
    'tradeup_daily',
    start_offset      => INTERVAL '3 days',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists     => TRUE
);

-- ── Initial backfill ─────────────────────────────────────────
-- Populate the last 30 days so default history queries have data
SELECT refresh_continuous_aggregate(
    'tradeup_daily',
    now() - INTERVAL '30 days',
    now() - INTERVAL '1 hour'
);
-- ── Indexes ───────────────────────────────────────────────────
-- idx_td_tradeup_bucket: supports per-trade-up history queries
--   WHERE tradeup_id = ? AND bucket BETWEEN ? AND ?
CREATE INDEX IF NOT EXISTS idx_td_tradeup_bucket
    ON tradeup_daily (tradeup_id, bucket DESC);

-- idx_td_bucket_avg_roi: supports top-N by ROI within a time window
--   WHERE bucket BETWEEN ? AND ? ORDER BY avg_roi DESC LIMIT ?
CREATE INDEX IF NOT EXISTS idx_td_bucket_avg_roi
    ON tradeup_daily (bucket, avg_roi DESC);

-- idx_td_bucket_avg_profit: supports top-N by profit within a time window
--   WHERE bucket BETWEEN ? AND ? ORDER BY avg_profit DESC LIMIT ?
CREATE INDEX IF NOT EXISTS idx_td_bucket_avg_profit
    ON tradeup_daily (bucket, avg_profit DESC);

-- ── Smoke test ────────────────────────────────────────────────
SELECT view_name, materialization_hypertable_name
FROM   timescaledb_information.continuous_aggregates
WHERE  view_name = 'tradeup_daily';

-- ============================================================
-- Migration 008: skin_price_daily Continuous Aggregate (optional)
-- ============================================================
-- Creates a TimescaleDB continuous aggregate (materialized view)
-- named skin_price_daily that pre-computes daily average, minimum
-- and maximum prices per skin / wear / source / currency from the
-- skin_price_history hypertable.
--
-- This aggregate can be used by price-history endpoints when
-- bucket = 1d is requested, providing faster reads than scanning
-- the raw hypertable.
--
-- When to use the aggregate vs. raw data:
--   • bucket = 1d  → prefer skin_price_daily (fast, indexed)
--   • bucket < 1d  → use raw skin_price_history with time_bucket()
--
-- The refresh policy re-materialises data once per hour,
-- covering a window from 3 days ago up to 1 hour ago.
--
-- Prerequisites: migrations 001–007 must have been applied.
-- ============================================================

-- ── Continuous aggregate ──────────────────────────────────────
CREATE MATERIALIZED VIEW IF NOT EXISTS skin_price_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', recorded_at)    AS bucket,
    skin_id,
    wear_id,
    source_id,
    currency_id,
    AVG(price)::NUMERIC(10, 2)           AS avg_price,
    MIN(price)::NUMERIC(10, 2)           AS min_price,
    MAX(price)::NUMERIC(10, 2)           AS max_price,
    COUNT(*)                             AS samples
FROM skin_price_history
GROUP BY bucket, skin_id, wear_id, source_id, currency_id
WITH NO DATA;

-- ── Refresh policy ────────────────────────────────────────────
SELECT add_continuous_aggregate_policy(
    'skin_price_daily',
    start_offset      => INTERVAL '3 days',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists     => TRUE
);

-- ── Initial backfill ─────────────────────────────────────────
-- Populate the last 30 days so price-history queries have data
-- immediately without waiting for the rolling policy window.
SELECT refresh_continuous_aggregate(
    'skin_price_daily',
    now() - INTERVAL '30 days',
    now() - INTERVAL '1 hour'
);

-- ── Indexes ───────────────────────────────────────────────────
-- idx_spd_skin_bucket: per-skin daily price history
--   WHERE skin_id = ? AND wear_id = ? AND bucket BETWEEN ? AND ?
CREATE INDEX IF NOT EXISTS idx_spd_skin_bucket
    ON skin_price_daily (skin_id, wear_id, bucket DESC);

-- idx_spd_source_currency: filter by source and currency
CREATE INDEX IF NOT EXISTS idx_spd_source_currency
    ON skin_price_daily (source_id, currency_id, bucket DESC);

-- ── Smoke test ────────────────────────────────────────────────
SELECT view_name, materialization_hypertable_name
FROM   timescaledb_information.continuous_aggregates
WHERE  view_name = 'skin_price_daily';

-- ============================================================
-- Migration 006: Add risk metric columns to tradeup_snapshots
-- ============================================================
-- Adds five nullable columns that capture the output-distribution
-- risk profile at the time each snapshot is calculated.
--
-- All columns are NULLABLE so that rows written by older code
-- (before this migration) remain valid.
--
--   prob_profit  fraction of outcomes whose value exceeds the input cost
--   variance     statistical variance of the outcome distribution
--   p05          5th-percentile outcome value (linear interpolation)
--   p50          50th-percentile (median) outcome value
--   p95          95th-percentile outcome value
-- ============================================================
ALTER TABLE tradeup_snapshots
    ADD COLUMN IF NOT EXISTS prob_profit DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS variance    DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS p05         DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS p50         DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS p95         DOUBLE PRECISION;

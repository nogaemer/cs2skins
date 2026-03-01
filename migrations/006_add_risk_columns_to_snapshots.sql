-- ============================================================
-- Migration 006: Add Risk Metric Columns to tradeup_snapshots
-- ============================================================
-- Adds nullable risk-metric columns to the tradeup_snapshots
-- hypertable so that each snapshot can carry a probability
-- distribution summary computed at write time.
--
-- All columns are NULLABLE so existing rows are unaffected and
-- the migration is safe to apply to a live database.
--
-- Column definitions:
--   prob_profit  fraction of outcome values > 0  (0.0 – 1.0)
--   variance     weighted variance of output distribution values
--   p05          5th  percentile of output distribution (weighted)
--   p50          50th percentile (median)
--   p95          95th percentile
--
-- NOTE: Because tradeup_snapshots is a TimescaleDB hypertable
-- with compression enabled (migration 003), any existing
-- compressed chunks must be decompressed before ALTER TABLE can
-- add new columns.  The DO block below handles this
-- automatically; the re-compression policy continues to run
-- normally after the migration.
--
-- Prerequisites: migrations 001–005 must have been applied.
-- ============================================================

-- ── Step 1: decompress all compressed chunks ──────────────────
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT chunk_schema, chunk_name
        FROM   timescaledb_information.chunks
        WHERE  hypertable_name = 'tradeup_snapshots'
          AND  is_compressed   = true
    LOOP
        PERFORM decompress_chunk(format('%I.%I', r.chunk_schema, r.chunk_name)::regclass);
    END LOOP;
END;
$$;

-- ── Step 2: add risk metric columns (no constraints so that ───
--            TimescaleDB columnstore restriction is satisfied)
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS prob_profit DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS variance    DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS p05         DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS p50         DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS p95         DOUBLE PRECISION;

-- ── Smoke test ────────────────────────────────────────────────
SELECT column_name, data_type, is_nullable
FROM   information_schema.columns
WHERE  table_name  = 'tradeup_snapshots'
  AND  column_name IN ('prob_profit', 'variance', 'p05', 'p50', 'p95')
ORDER  BY column_name;

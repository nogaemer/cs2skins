-- ============================================================
-- Migration 009: Add Financial Metric Columns
-- ============================================================
-- Adds nullable financial metric columns to both
-- tradeups_current and tradeup_snapshots so that each record
-- can carry the full set of cost/profit/ROI values:
--
--   input_cost_no_drop_change   total input cost (no drop-change adjustment)
--   profit_no_drop_change       profit without drop-change adjustment
--   roi_no_drop_change          ROI without drop-change adjustment
--   profit_chance               fraction of outcomes where output >= input cost
--
-- Existing columns (input_cost, profit, roi, output_cost) store the
-- "with drop-change" variants and are left unchanged.
--
-- All columns are NULLABLE so existing rows are unaffected and
-- the migration is safe to apply to a live database.
--
-- NOTE: Because tradeup_snapshots is a TimescaleDB hypertable
-- with compression enabled, any existing compressed chunks must
-- be decompressed before ALTER TABLE can add new columns.
-- The DO block below handles this automatically.
--
-- Prerequisites: migrations 001–008 must have been applied.
-- ============================================================

-- ── Step 1: decompress all compressed chunks in tradeup_snapshots ──
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

-- ── Step 2: add columns to tradeups_current ───────────────────
ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS input_cost_no_drop_change DOUBLE PRECISION;
ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS profit_no_drop_change     DOUBLE PRECISION;
ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS roi_no_drop_change        DOUBLE PRECISION;
ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS profit_chance             DOUBLE PRECISION;

-- ── Step 3: add columns to tradeup_snapshots ──────────────────
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS input_cost_no_drop_change DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS profit_no_drop_change     DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS roi_no_drop_change        DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS profit_chance             DOUBLE PRECISION;

-- ── Smoke test ────────────────────────────────────────────────
SELECT table_name, column_name, data_type, is_nullable
FROM   information_schema.columns
WHERE  table_name  IN ('tradeups_current', 'tradeup_snapshots')
  AND  column_name IN ('input_cost_no_drop_change', 'profit_no_drop_change',
                       'roi_no_drop_change', 'profit_chance')
ORDER  BY table_name, column_name;

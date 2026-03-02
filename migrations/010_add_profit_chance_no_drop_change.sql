-- ============================================================
-- Migration 010: Add profit_chance_no_drop_change Column and
--                Rebuild tradeup_daily with New Metric Columns
-- ============================================================
-- 1. Adds profit_chance_no_drop_change to tradeups_current and
--    tradeup_snapshots.  profit_chance_no_drop_change is the
--    fraction of outcomes where output value ≥ input cost
--    (no drop-change adjustment), complementing profit_chance
--    which uses inputCostWithDropChange as the threshold.
--
-- 2. Drops and recreates the tradeup_daily continuous aggregate
--    to include all five new metric columns added in migrations
--    009 and 010.  TimescaleDB continuous aggregates do not
--    support ALTER to add columns, so a drop-then-recreate is
--    required.  This causes the aggregate data to be re-filled
--    by the refresh policy on its next run (or immediately via
--    CALL refresh_continuous_aggregate).
--
-- All new table columns are NULLABLE so existing rows are
-- unaffected; the migration is safe to apply to a live database.
--
-- Prerequisites: migrations 001–009 must have been applied.
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

-- ── Step 2: add profit_chance_no_drop_change columns ──────────
ALTER TABLE tradeups_current  ADD COLUMN IF NOT EXISTS profit_chance_no_drop_change DOUBLE PRECISION;
ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS profit_chance_no_drop_change DOUBLE PRECISION;

-- ── Step 3: rebuild tradeup_daily with all new metric columns ──
--
-- Remove the old refresh policy (required before dropping the view).
SELECT remove_continuous_aggregate_policy('tradeup_daily', if_not_exists => TRUE);

-- Drop the old continuous aggregate (CASCADE removes dependent indexes).
DROP MATERIALIZED VIEW IF EXISTS tradeup_daily CASCADE;

-- Recreate with full column set.
CREATE MATERIALIZED VIEW tradeup_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', snapshot_time)        AS bucket,
    tradeup_id,
    AVG(roi)                                   AS avg_roi,
    AVG(profit)                                AS avg_profit,
    AVG(input_cost)                            AS avg_input_cost,
    AVG(output_cost)                           AS avg_output_cost,
    AVG(input_cost_no_drop_change)             AS avg_input_cost_no_drop_change,
    AVG(profit_no_drop_change)                 AS avg_profit_no_drop_change,
    AVG(roi_no_drop_change)                    AS avg_roi_no_drop_change,
    AVG(profit_chance)                         AS avg_profit_chance,
    AVG(profit_chance_no_drop_change)          AS avg_profit_chance_no_drop_change,
    COUNT(*)                                   AS samples
FROM tradeup_snapshots
GROUP BY bucket, tradeup_id
WITH NO DATA;

-- Reattach refresh policy
SELECT add_continuous_aggregate_policy(
    'tradeup_daily',
    start_offset      => INTERVAL '3 days',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists     => TRUE
);

-- Backfill last 30 days
SELECT refresh_continuous_aggregate(
    'tradeup_daily',
    now() - INTERVAL '30 days',
    now() - INTERVAL '1 hour'
);

-- Recreate indexes
CREATE INDEX IF NOT EXISTS idx_td_tradeup_bucket
    ON tradeup_daily (tradeup_id, bucket DESC);

CREATE INDEX IF NOT EXISTS idx_td_bucket_avg_roi
    ON tradeup_daily (bucket, avg_roi DESC);

CREATE INDEX IF NOT EXISTS idx_td_bucket_avg_profit
    ON tradeup_daily (bucket, avg_profit DESC);

-- ── Smoke test ────────────────────────────────────────────────
SELECT column_name
FROM   information_schema.columns
WHERE  table_name  IN ('tradeups_current', 'tradeup_snapshots')
  AND  column_name = 'profit_chance_no_drop_change'
ORDER  BY table_name;

SELECT view_name, materialization_hypertable_name
FROM   timescaledb_information.continuous_aggregates
WHERE  view_name = 'tradeup_daily';

-- ============================================================================
-- Adds skin_1_wear_bucket_id / skin_2_wear_bucket_id to tradeup_snapshot_raw
-- so downstream analytics can join straight to item_current_prices /
-- item_price_history_raw on (item_id, wear_bucket_id) without recomputing
-- CSWear.floatToCSWear(skin_N_float) at query time.
--
-- Safe to run now: tradeup_snapshot_raw / tradeup_snapshot_latest were
-- already truncated in the previous migration, so this is a pure schema
-- change with nothing to backfill.
-- ============================================================================

ALTER TABLE tradeups.tradeup_snapshot_raw
    ADD COLUMN IF NOT EXISTS skin_1_wear_bucket_id UInt8,
    ADD COLUMN IF NOT EXISTS skin_2_wear_bucket_id UInt8;

-- tradeup_snapshot_latest is a materialized-view TARGET; its column list is
-- fixed by the MV's SELECT at creation time, so both the MV and target table
-- need to be recreated to pick up the new columns.
DROP TABLE IF EXISTS tradeups.mv_tradeup_snapshot_latest;
DROP TABLE IF EXISTS tradeups.tradeup_snapshot_latest;

CREATE TABLE tradeups.tradeup_snapshot_latest
(
    tradeup_recipe_id UInt64,
    snapshot_at DateTime64(3),
    run_id UInt64,
    wear_bucket_id UInt8,
    skin_1_item_id UInt64,
    skin_2_item_id UInt64,
    skin_1_count UInt8,
    skin_2_count UInt8,
    skin_1_wear_bucket_id UInt8,
    skin_2_wear_bucket_id UInt8,
    skin_1_float Float32,
    skin_2_float Float32,
    average_input_float Float32,
    average_raw_input_float Float32,
    input_cost Decimal(18,4),
    input_cost_with_drop_change Decimal(18,4),
    expected_value Decimal(18,4),
    profit_abs Decimal(18,4),
    profit_with_drop_change Decimal(18,4),
    roi Float32,
    roi_with_drop_change Float32,
    profit_chance Float32,
    profit_percentage Float32,
    outcome_count UInt16,
    algorithm_version LowCardinality(String)
)
ENGINE = ReplacingMergeTree(snapshot_at)
ORDER BY (tradeup_recipe_id, wear_bucket_id);

CREATE MATERIALIZED VIEW tradeups.mv_tradeup_snapshot_latest
    TO tradeups.tradeup_snapshot_latest
AS
SELECT
    tradeup_recipe_id, snapshot_at, run_id, wear_bucket_id,
    skin_1_item_id, skin_2_item_id, skin_1_count, skin_2_count,
    skin_1_wear_bucket_id, skin_2_wear_bucket_id,
    skin_1_float, skin_2_float, average_input_float, average_raw_input_float,
    input_cost, input_cost_with_drop_change, expected_value, profit_abs,
    profit_with_drop_change, roi, roi_with_drop_change, profit_chance,
    profit_percentage, outcome_count, algorithm_version
FROM tradeups.tradeup_snapshot_raw;

-- Mirror this into infra/clickhouse/init/002_tables.sql (add the two columns
-- to tradeup_snapshot_raw and tradeup_snapshot_latest's CREATE TABLE) and
-- infra/clickhouse/init/003_views.sql (add the two columns to the MV SELECT
-- list) so a fresh volume bootstraps with this already in place.

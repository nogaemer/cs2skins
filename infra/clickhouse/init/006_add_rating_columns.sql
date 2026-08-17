--
-- Migration: rating, depth_gate, volatility_combined_7d on
-- tradeup_snapshot_raw / tradeup_snapshot_latest
--
-- rating: 0-100, weighted geometric mean of ROI/profit-chance/execution-cost
--   /volatility/liquidity scores, computed by RatingCalculator.kt.
-- depth_gate: 1.0 normally, 0.1 if openskin reported insufficient order-book
--   depth to fill either input leg's required quantity (see
--   price_impact_5_pct / price_impact_10_pct being NULL in
--   item_current_prices). Multiplied into rating AFTER the geometric mean --
--   a hard feasibility penalty, not something the other components can
--   average away.
-- volatility_combined_7d: sqrt(volatility_input_7d^2 + volatility_output_7d^2)
--   -- the compounded price risk of holding first the input skins, then the
--   output skin, through their respective mandatory 7-day Steam trade locks
--   (two sequential exposure windows, not one).
--
-- tradeup_snapshot_raw is a plain MergeTree (not an MV target) -- ADD COLUMN
-- works in place, same as migration 004's skin1_wear_bucket_id.
--
ALTER TABLE tradeups.tradeup_snapshot_raw
    ADD COLUMN IF NOT EXISTS rating Float32 CODEC(Gorilla, ZSTD),
    ADD COLUMN IF NOT EXISTS depth_gate Float32 CODEC(Gorilla, ZSTD),
    ADD COLUMN IF NOT EXISTS volatility_combined_7d Float32 CODEC(Gorilla, ZSTD);

-- tradeup_snapshot_latest is a materialized-view TARGET -- its column list is
-- fixed by the MV's SELECT at creation time, so (same as every prior
-- migration touching it) both the MV and the target table need to be
-- recreated to pick up the new columns.
DROP TABLE IF EXISTS tradeups.mv_tradeup_snapshot_latest;
DROP TABLE IF EXISTS tradeups.tradeup_snapshot_latest;

CREATE TABLE tradeups.tradeup_snapshot_latest (
    tradeup_recipe_id UUID,
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
    rating Float32,
    depth_gate Float32,
    volatility_combined_7d Float32,
    algorithm_version LowCardinality(String)
) ENGINE = ReplacingMergeTree(snapshot_at)
ORDER BY (tradeup_recipe_id, wear_bucket_id);

CREATE MATERIALIZED VIEW tradeups.mv_tradeup_snapshot_latest
    TO tradeups.tradeup_snapshot_latest AS
SELECT
    tradeup_recipe_id, snapshot_at, run_id, wear_bucket_id,
    skin_1_item_id, skin_2_item_id, skin_1_count, skin_2_count,
    skin_1_wear_bucket_id, skin_2_wear_bucket_id,
    skin_1_float, skin_2_float, average_input_float, average_raw_input_float,
    input_cost, input_cost_with_drop_change, expected_value,
    profit_abs, profit_with_drop_change,
    roi, roi_with_drop_change, profit_chance, profit_percentage,
    outcome_count, rating, depth_gate, volatility_combined_7d, algorithm_version
FROM tradeups.tradeup_snapshot_raw;

-- Mirror this into infra/clickhouse/init/002_tables.sql (add the three
-- columns to tradeup_snapshot_raw and tradeup_snapshot_latest's CREATE
-- TABLE) and infra/clickhouse/init/003_views.sql (add them to the MV SELECT
-- list) so a fresh volume bootstraps with this already in place.

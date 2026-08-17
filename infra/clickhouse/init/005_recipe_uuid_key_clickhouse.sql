-- ============================================================================
-- Migration: tradeup_recipe_id UInt64 -> UUID (Phase 2b, ClickHouse side)
--
-- ClickHouse can't ALTER a UInt64 column to UUID in place, so this drops and
-- recreates the 4 affected tables + 2 materialized views. Same dev-data
-- policy as before -- run PostgresSeedService + optimizeAll() again
-- afterward to repopulate.
-- ============================================================================

DROP TABLE IF EXISTS tradeups.mv_tradeup_snapshot_latest;
DROP TABLE IF EXISTS tradeups.mv_tradeup_snapshot_rollup_1d;
DROP TABLE IF EXISTS tradeups.tradeup_snapshot_latest;
DROP TABLE IF EXISTS tradeups.tradeup_snapshot_rollup_1d;
DROP TABLE IF EXISTS tradeups.tradeup_snapshot_raw;
DROP TABLE IF EXISTS tradeups.tradeup_outcome_snapshot_raw;

CREATE TABLE tradeups.tradeup_snapshot_raw
(
    snapshot_at DateTime64(3) CODEC(DoubleDelta, ZSTD),
    snapshot_date Date MATERIALIZED toDate(snapshot_at),
    run_id UInt64,
    tradeup_recipe_id UUID,
    skin_1_item_id UInt64,
    skin_2_item_id UInt64,
    skin_1_count UInt8,
    skin_2_count UInt8,
    wear_bucket_id UInt8,
    skin_1_wear_bucket_id UInt8,
    skin_2_wear_bucket_id UInt8,
    skin_1_float Float32 CODEC(Gorilla, ZSTD),
    skin_2_float Float32 CODEC(Gorilla, ZSTD),
    average_input_float Float32 CODEC(Gorilla, ZSTD),
    average_raw_input_float Float32 CODEC(Gorilla, ZSTD),
    input_cost Decimal(18,4),
    input_cost_with_drop_change Decimal(18,4),
    expected_value Decimal(18,4),
    profit_abs Decimal(18,4),
    profit_with_drop_change Decimal(18,4),
    roi Float32 CODEC(Gorilla, ZSTD),
    roi_with_drop_change Float32 CODEC(Gorilla, ZSTD),
    profit_chance Float32 CODEC(Gorilla, ZSTD),
    profit_percentage Float32 CODEC(Gorilla, ZSTD),
    outcome_count UInt16,
    algorithm_version LowCardinality(String)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(snapshot_at)
ORDER BY (tradeup_recipe_id, wear_bucket_id, snapshot_at);

CREATE TABLE tradeups.tradeup_outcome_snapshot_raw
(
    snapshot_at DateTime64(3) CODEC(DoubleDelta, ZSTD),
    snapshot_date Date MATERIALIZED toDate(snapshot_at),
    run_id UInt64,
    tradeup_recipe_id UUID,
    outcome_item_id UInt64,
    outcome_index UInt16,
    output_float Float32 CODEC(Gorilla, ZSTD),
    output_wear_bucket_id UInt8,
    outcome_probability Float32 CODEC(Gorilla, ZSTD),
    outcome_price Decimal(18,4),
    expected_contribution Decimal(18,4),
    algorithm_version LowCardinality(String)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(snapshot_at)
ORDER BY (tradeup_recipe_id, snapshot_at, outcome_index, outcome_item_id);

CREATE TABLE tradeups.tradeup_snapshot_latest
(
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
    algorithm_version LowCardinality(String)
)
ENGINE = ReplacingMergeTree(snapshot_at)
ORDER BY (tradeup_recipe_id, wear_bucket_id);

CREATE TABLE tradeups.tradeup_snapshot_rollup_1d
(
    bucket_date Date,
    tradeup_recipe_id UUID,
    wear_bucket_id UInt8,
    avg_input_cost AggregateFunction(avg, Decimal(18,4)),
    min_input_cost AggregateFunction(min, Decimal(18,4)),
    max_input_cost AggregateFunction(max, Decimal(18,4)),
    avg_expected_value AggregateFunction(avg, Decimal(18,4)),
    avg_profit AggregateFunction(avg, Decimal(18,4)),
    min_profit AggregateFunction(min, Decimal(18,4)),
    max_profit AggregateFunction(max, Decimal(18,4)),
    avg_roi AggregateFunction(avg, Float32),
    avg_input_float AggregateFunction(avg, Float32),
    avg_raw_input_float AggregateFunction(avg, Float32)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(bucket_date)
ORDER BY (tradeup_recipe_id, wear_bucket_id, bucket_date);

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

CREATE MATERIALIZED VIEW tradeups.mv_tradeup_snapshot_rollup_1d
    TO tradeups.tradeup_snapshot_rollup_1d
AS
SELECT
    toDate(snapshot_at) AS bucket_date,
    tradeup_recipe_id,
    wear_bucket_id,
    avgState(input_cost) AS avg_input_cost,
    minState(input_cost) AS min_input_cost,
    maxState(input_cost) AS max_input_cost,
    avgState(expected_value) AS avg_expected_value,
    avgState(profit_abs) AS avg_profit,
    minState(profit_abs) AS min_profit,
    maxState(profit_abs) AS max_profit,
    avgState(roi) AS avg_roi,
    avgState(average_input_float) AS avg_input_float,
    avgState(average_raw_input_float) AS avg_raw_input_float
FROM tradeups.tradeup_snapshot_raw
GROUP BY bucket_date, tradeup_recipe_id, wear_bucket_id;

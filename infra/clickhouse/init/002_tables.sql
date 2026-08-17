CREATE TABLE IF NOT EXISTS tradeups.item_price_history_raw
(
    observed_at DateTime64(3) CODEC(DoubleDelta, ZSTD),
    observed_date Date MATERIALIZED toDate(observed_at),
    item_id UInt64,
    wear_bucket_id UInt8,
    price_source_id UInt8,
    buy_price Decimal(18,4),
    sell_price Decimal(18,4),
    average_price Decimal(18,4),
    volume_24h UInt32,
    listings UInt32,
    liquidity_score Float32 CODEC(Gorilla, ZSTD),
    currency_code LowCardinality(String)
    )
    ENGINE = MergeTree
    PARTITION BY toYYYYMM(observed_at)
    ORDER BY (item_id, wear_bucket_id, price_source_id, observed_at);

CREATE TABLE IF NOT EXISTS tradeups.tradeup_snapshot_raw
(
    snapshot_at DateTime64(3) CODEC(DoubleDelta, ZSTD),
    snapshot_date Date MATERIALIZED toDate(snapshot_at),
    run_id UInt64,
    tradeup_recipe_id UInt64,
    skin_1_item_id UInt64,
    skin_2_item_id UInt64,
    skin_1_count UInt8,
    skin_2_count UInt8,
    wear_bucket_id UInt8,

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

CREATE TABLE IF NOT EXISTS tradeups.tradeup_outcome_snapshot_raw
(
    snapshot_at DateTime64(3) CODEC(DoubleDelta, ZSTD),
    snapshot_date Date MATERIALIZED toDate(snapshot_at),
    run_id UInt64,
    tradeup_recipe_id UInt64,
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

CREATE TABLE IF NOT EXISTS tradeups.tradeup_snapshot_latest
(
    tradeup_recipe_id UInt64,
    snapshot_at DateTime64(3),
    run_id UInt64,
    wear_bucket_id UInt8,
    skin_1_item_id UInt64,
    skin_2_item_id UInt64,
    skin_1_count UInt8,
    skin_2_count UInt8,
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

CREATE TABLE IF NOT EXISTS tradeups.tradeup_snapshot_rollup_1d
(
    bucket_date Date,
    tradeup_recipe_id UInt64,
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
CREATE MATERIALIZED VIEW IF NOT EXISTS tradeups.mv_tradeup_snapshot_latest
            TO tradeups.tradeup_snapshot_latest
AS
SELECT
    tradeup_recipe_id,
    snapshot_at,
    run_id,
    wear_bucket_id,
    skin_1_item_id,
    skin_2_item_id,
    skin_1_count,
    skin_2_count,
    skin_1_float,
    skin_2_float,
    average_input_float,
    average_raw_input_float,
    input_cost,
    input_cost_with_drop_change,
    expected_value,
    profit_abs,
    profit_with_drop_change,
    roi,
    roi_with_drop_change,
    profit_chance,
    profit_percentage,
    outcome_count,
    algorithm_version
FROM tradeups.tradeup_snapshot_raw;

CREATE MATERIALIZED VIEW IF NOT EXISTS tradeups.mv_tradeup_snapshot_rollup_1d
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
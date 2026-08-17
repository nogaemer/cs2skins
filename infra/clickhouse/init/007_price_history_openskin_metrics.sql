--
-- Migration: openskin microstructure metrics on item_price_history_raw
--
-- Postgres's item_current_prices got spread_pct/slippage_pct/price_impact_
-- 5_pct/price_impact_10_pct/volatility_1d/volatility_7d in migration 006 --
-- but that table overwrites in place (ON CONFLICT DO UPDATE), so it only
-- ever holds the LATEST snapshot. item_price_history_raw is the actual
-- time-series history (append-only, every ingestion run adds new rows,
-- never overwrites) -- this is where these metrics need to land for there
-- to be any historical data to work with later (trend analysis, volatility
-- regime changes over time, etc.), rather than only ever seeing "now".
--
-- item_price_history_raw is a plain MergeTree, not a materialized-view
-- target -- ADD COLUMN works in place, same as migration 004's
-- skin_1_wear_bucket_id addition to tradeup_snapshot_raw. No MV/target-table
-- drop-and-recreate needed here.
--
ALTER TABLE tradeups.item_price_history_raw
    ADD COLUMN IF NOT EXISTS spread_pct Float32 CODEC(Gorilla, ZSTD),
    ADD COLUMN IF NOT EXISTS slippage_pct Float32 CODEC(Gorilla, ZSTD),
    -- Nullable(Float32), not plain Float32 -- a level being entirely absent
    -- from openskin's response (insufficient order-book depth) is a
    -- meaningful signal in itself, distinct from "not measured yet"; NULL
    -- preserves that distinction instead of collapsing it to 0.
    ADD COLUMN IF NOT EXISTS price_impact_5_pct Nullable(Float32) CODEC(ZSTD),
    ADD COLUMN IF NOT EXISTS price_impact_10_pct Nullable(Float32) CODEC(ZSTD),
    ADD COLUMN IF NOT EXISTS volatility_1d Float32 CODEC(Gorilla, ZSTD),
    ADD COLUMN IF NOT EXISTS volatility_7d Float32 CODEC(Gorilla, ZSTD);

-- Mirror this into infra/clickhouse/init/002_tables.sql (add the six
-- columns to item_price_history_raw's CREATE TABLE) so a fresh volume
-- bootstraps with this already in place.

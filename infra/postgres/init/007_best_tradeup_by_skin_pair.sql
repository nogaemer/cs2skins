--
-- Migration: best_tradeup_by_skin_pair
--
-- Purpose-built lookup table for "what's the best-rated trade-up for skin A
-- + skin B, across every wear bucket / count-split / stattrak variant". This
-- is NOT a live-maintained view -- it's a plain table, fully repopulated as
-- a batch step at the end of every TradeUpOptimizer.optimizeAll() run, via a
-- single argMax aggregate query against ClickHouse's tradeup_snapshot_latest
-- (see RatingCalculator.kt / TradeUpOptimizer wiring notes).
--
-- rating/roi/profit_chance are denormalized copies of the winning recipe's
-- ClickHouse snapshot values at the time best_tradeup_by_skin_pair was last
-- refreshed -- they exist so callers can render a result list without a
-- second round-trip to ClickHouse. best_recipe_id is the join key back to
-- tradeup_recipes (and, via that, to the live ClickHouse snapshot for
-- current price data).
--
CREATE TABLE IF NOT EXISTS best_tradeup_by_skin_pair (
    skin_1_item_id BIGINT NOT NULL REFERENCES items(id),
    skin_2_item_id BIGINT NOT NULL REFERENCES items(id),
    best_recipe_id UUID NOT NULL REFERENCES tradeup_recipes(id),
    best_rating    REAL NOT NULL,
    best_roi_with_drop_change REAL NOT NULL,
    best_profit_chance REAL NOT NULL,
    computed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (skin_1_item_id, skin_2_item_id),
    CHECK (skin_1_item_id <= skin_2_item_id)
);

CREATE INDEX IF NOT EXISTS idx_best_tradeup_rating
    ON best_tradeup_by_skin_pair (best_rating DESC);

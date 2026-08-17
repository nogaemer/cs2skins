--
-- Migration: openskin microstructure metrics on item_current_prices
--
-- item_current_prices today only carries buy_price/sell_price/average_price/
-- volume_24h/listings/liquidity_score. openskin's /v1/prices/batch response
-- also exposes spread, slippage, price_impact (at 1/5/10/25-unit depth), and
-- volatility (1d/7d/30d/90d/all) -- none of which are being persisted. These
-- are exactly the signals the trade-up rating needs (execution cost +
-- depth-sufficiency + price risk during the mandatory 7-day trade lock).
--
-- Dev-data policy: item_current_prices is regenerable via
-- PriceIngestionService.ingestCurrentPrices, so this is a plain ADD COLUMN
-- with no backfill -- next ingestion run populates the new columns.

ALTER TABLE item_current_prices
    ADD COLUMN IF NOT EXISTS spread_pct        NUMERIC(10,4),
    ADD COLUMN IF NOT EXISTS slippage_pct       NUMERIC(10,4),
    -- price_impact at 5-unit depth: the "can I actually buy what a typical
    -- trade-up leg needs" signal. NULL means openskin had insufficient order
    -- book depth to fill 5 units -- treat NULL here as a hard liquidity
    -- signal, not missing data, in the rating calculation.
    ADD COLUMN IF NOT EXISTS price_impact_5_pct NUMERIC(10,4),
    -- Same idea at 10-unit depth, since some trade-up legs need up to 9
    -- units of a single input skin (skin1_count/skin2_count range 1..9).
    ADD COLUMN IF NOT EXISTS price_impact_10_pct NUMERIC(10,4),
    ADD COLUMN IF NOT EXISTS volatility_1d      NUMERIC(10,6),
    ADD COLUMN IF NOT EXISTS volatility_7d      NUMERIC(10,6);

COMMENT ON COLUMN item_current_prices.spread_pct IS
    'openskin spread.percent -- bid-ask spread relative to bid';
COMMENT ON COLUMN item_current_prices.slippage_pct IS
    'openskin slippage -- (median - bid) / median * 100';
COMMENT ON COLUMN item_current_prices.price_impact_5_pct IS
    'openskin price_impact[5].pct_above_ask -- NULL means insufficient depth to fill 5 units';
COMMENT ON COLUMN item_current_prices.price_impact_10_pct IS
    'openskin price_impact[10].pct_above_ask -- NULL means insufficient depth to fill 10 units';
COMMENT ON COLUMN item_current_prices.volatility_1d IS
    'openskin volatility.1d -- std dev of log returns, trailing 1 day';
COMMENT ON COLUMN item_current_prices.volatility_7d IS
    'openskin volatility.7d -- std dev of log returns, trailing 7 days (matches the Steam trade-lock window)';

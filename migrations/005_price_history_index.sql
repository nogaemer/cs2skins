-- ============================================================
-- Migration 005: Price History Lookup Index
-- ============================================================
-- Adds a composite index on skin_price_history that covers the
-- most common access pattern used by the price-history API
-- (filter by skin_id, wear_id, source_id, currency_id and
-- order by recorded_at DESC).  The index is created with
-- IF NOT EXISTS so the migration is safe to re-run.
--
-- NOTE: TimescaleDB distributes data across time-based chunks.
-- An index created on the parent hypertable is automatically
-- propagated to all existing and future chunks.
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_sph_lookup
    ON skin_price_history (skin_id, wear_id, source_id, currency_id, recorded_at DESC);

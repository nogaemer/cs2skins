-- ============================================================
-- Migration 004: Multi-Source / Multi-Currency Pricing
-- ============================================================
-- Adds price_sources and currencies reference tables, then
-- extends skin_prices_current and skin_price_history to carry
-- source_id and currency_id so that prices from different
-- providers (Steam, CSFloat, Buff163, …) and in different
-- currencies (USD, EUR, CNY, …) can be stored side-by-side.
--
-- Safe migration path
-- ────────────────────
-- 1. Create new reference tables and seed rows.
-- 2. Add source_id / currency_id as NULLable columns.
-- 3. Back-fill existing rows with Steam / USD defaults.
-- 4. Tighten columns to NOT NULL.
-- 5. Drop old PK / unique constraints and add new composite ones.
-- 6. Update compression segmentby on skin_price_history to
--    include the two new columns for optimal query performance.
--
-- Prerequisites: migrations 001–003 must have been applied.
-- ============================================================

-- ============================================================
-- Reference tables
-- ============================================================

CREATE TABLE price_sources (
    id       SERIAL PRIMARY KEY,
    name     TEXT    UNIQUE NOT NULL,
    base_url TEXT,
    active   BOOLEAN NOT NULL DEFAULT true
);

INSERT INTO price_sources (name, base_url) VALUES
    ('steam',   'https://steamcommunity.com/market'),
    ('csfloat', 'https://csfloat.com'),
    ('buff163', 'https://buff.163.com');

CREATE TABLE currencies (
    id      SERIAL PRIMARY KEY,
    code    CHAR(3) UNIQUE NOT NULL,  -- ISO 4217
    symbol  TEXT,
    is_base BOOLEAN NOT NULL DEFAULT false
);

INSERT INTO currencies (code, symbol, is_base) VALUES
    ('USD', '$',  true),
    ('EUR', '€',  false),
    ('CNY', '¥',  false);

-- ============================================================
-- skin_prices_current
-- ============================================================

-- Step 1: add NULLable columns.
ALTER TABLE skin_prices_current
    ADD COLUMN source_id   INT REFERENCES price_sources(id),
    ADD COLUMN currency_id INT REFERENCES currencies(id);

-- Step 2: back-fill existing rows with Steam / USD defaults.
UPDATE skin_prices_current
SET source_id   = (SELECT id FROM price_sources WHERE name = 'steam'),
    currency_id = (SELECT id FROM currencies   WHERE code = 'USD')
WHERE source_id IS NULL;

-- Step 3: drop old two-column PK, tighten nullability, add new PK.
ALTER TABLE skin_prices_current
    DROP CONSTRAINT skin_prices_current_pkey,
    ALTER COLUMN source_id   SET NOT NULL,
    ALTER COLUMN currency_id SET NOT NULL,
    ADD  CONSTRAINT skin_prices_current_pkey
         PRIMARY KEY (skin_id, wear_id, source_id, currency_id);

-- ============================================================
-- skin_price_history
-- ============================================================

-- Step 1: add NULLable columns.
ALTER TABLE skin_price_history
    ADD COLUMN source_id   INT REFERENCES price_sources(id),
    ADD COLUMN currency_id INT REFERENCES currencies(id);

-- Step 2: back-fill existing rows.
UPDATE skin_price_history
SET source_id   = (SELECT id FROM price_sources WHERE name = 'steam'),
    currency_id = (SELECT id FROM currencies   WHERE code = 'USD')
WHERE source_id IS NULL;

-- Step 3: tighten nullability.
ALTER TABLE skin_price_history
    ALTER COLUMN source_id   SET NOT NULL,
    ALTER COLUMN currency_id SET NOT NULL;

-- Step 4: add a unique constraint that covers the full logical key.
--         The TimescaleDB requirement that the time column must be
--         part of every UNIQUE/PK constraint on a hypertable is
--         satisfied by including recorded_at.
ALTER TABLE skin_price_history
    DROP CONSTRAINT IF EXISTS skin_price_history_unique,
    ADD  CONSTRAINT skin_price_history_unique
         UNIQUE (skin_id, wear_id, source_id, currency_id, recorded_at);

-- ============================================================
-- Update compression segmentby to include new columns
-- ============================================================
-- Adding source_id and currency_id to compress_segmentby lets
-- the query planner skip decompression for unrelated source /
-- currency combinations, which is the typical access pattern
-- after this migration.
--
-- NOTE: This ALTER requires that no chunks are currently
-- compressed.  On a fresh or dev database this is always safe.
-- On a production system, run decompress_chunk() for any
-- compressed chunks first, then re-run this statement.
ALTER TABLE skin_price_history
    SET (
        timescaledb.compress,
        timescaledb.compress_segmentby = 'skin_id, wear_id, source_id, currency_id',
        timescaledb.compress_orderby   = 'recorded_at DESC'
    );

-- ============================================================
-- Smoke test: confirm new tables and updated constraints.
-- ============================================================
SELECT table_name
FROM   information_schema.tables
WHERE  table_schema = 'public'
  AND  table_name   IN ('price_sources', 'currencies')
ORDER  BY table_name;

SELECT constraint_name, table_name
FROM   information_schema.table_constraints
WHERE  table_schema     = 'public'
  AND  constraint_type  = 'PRIMARY KEY'
  AND  table_name       = 'skin_prices_current';

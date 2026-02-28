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
-- 2. Add source_id / currency_id as plain NULLable INT columns
--    (NO inline REFERENCES — columnstore/hypertable restriction).
-- 3. Add FK constraints as separate ALTER TABLE statements.
-- 4. Back-fill existing rows with Steam / USD defaults.
-- 5. Tighten columns to NOT NULL (separate statements).
-- 6. Drop old PK / unique constraints and add new composite ones.
-- 7. Decompress all compressed chunks, update compression
--    segmentby to include the two new columns, then re-enable
--    the compression policy.
--
-- ──────────────────────────────────────────────────────────────
-- WHY CONSTRAINTS ARE SPLIT INTO SEPARATE ALTER TABLE STATEMENTS
-- ──────────────────────────────────────────────────────────────
-- TimescaleDB raises:
--   ERROR 0A000: cannot add column with constraints to a hypertable
--                that has columnstore enabled
-- when ADD COLUMN and REFERENCES (or NOT NULL) appear in the same
-- ALTER TABLE.  The workaround is:
--   1. ADD COLUMN col INT;              -- no constraints
--   2. ALTER TABLE ADD CONSTRAINT ...;  -- FK as own statement
--   3. UPDATE ... (back-fill)
--   4. ALTER COLUMN col SET NOT NULL;   -- own statement
-- skin_prices_current is a regular table so the combined form
-- would work there too, but we use the split form consistently
-- for clarity.
--
-- Prerequisites: migrations 001–003 must have been applied.
-- ============================================================

-- ============================================================
-- Reference tables
-- ============================================================

CREATE TABLE IF NOT EXISTS price_sources (
    id       SERIAL PRIMARY KEY,
    name     TEXT    UNIQUE NOT NULL,
    base_url TEXT,
    active   BOOLEAN NOT NULL DEFAULT true
);

INSERT INTO price_sources (name, base_url) VALUES
    ('steam',   'https://steamcommunity.com/market'),
    ('csfloat', 'https://csfloat.com'),
    ('buff163', 'https://buff.163.com')
ON CONFLICT (name) DO NOTHING;

CREATE TABLE IF NOT EXISTS currencies (
    id      SERIAL PRIMARY KEY,
    code    CHAR(3) UNIQUE NOT NULL,  -- ISO 4217
    symbol  TEXT,
    is_base BOOLEAN NOT NULL DEFAULT false
);

INSERT INTO currencies (code, symbol, is_base) VALUES
    ('USD', '$',  true),
    ('EUR', '€',  false),
    ('CNY', '¥',  false)
ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- skin_prices_current  (regular table – no columnstore)
-- ============================================================

-- Step 1: add plain NULLable INT columns (no inline REFERENCES).
ALTER TABLE skin_prices_current
    ADD COLUMN IF NOT EXISTS source_id   INT,
    ADD COLUMN IF NOT EXISTS currency_id INT;

-- Step 2: add FK constraints as separate statements.
ALTER TABLE skin_prices_current
    ADD CONSTRAINT fk_spc_source   FOREIGN KEY (source_id)
        REFERENCES price_sources(id);
ALTER TABLE skin_prices_current
    ADD CONSTRAINT fk_spc_currency FOREIGN KEY (currency_id)
        REFERENCES currencies(id);

-- Step 3: back-fill existing rows with Steam / USD defaults.
-- No WHERE clause: unconditional UPDATE is safe (setting Steam/USD on rows
-- that already carry those values is a no-op) and avoids the
-- "WHERE condition is always false" warning that fires on re-runs once
-- the columns are already NOT NULL.
UPDATE skin_prices_current
SET source_id   = (SELECT id FROM price_sources WHERE name = 'steam'),
    currency_id = (SELECT id FROM currencies   WHERE code = 'USD')
WHERE source_id IS NULL OR currency_id IS NULL;

-- Step 4: tighten nullability (separate statements required).
ALTER TABLE skin_prices_current ALTER COLUMN source_id   SET NOT NULL;
ALTER TABLE skin_prices_current ALTER COLUMN currency_id SET NOT NULL;

-- Step 5: drop old two-column PK and add new four-column PK.
ALTER TABLE skin_prices_current
    DROP CONSTRAINT IF EXISTS pk_skin_prices_current;
ALTER TABLE skin_prices_current
    ADD CONSTRAINT pk_skin_prices_current
        PRIMARY KEY (skin_id, wear_id, source_id, currency_id);

-- ============================================================
-- skin_price_history  (hypertable WITH columnstore enabled)
-- ============================================================
-- TimescaleDB ERROR 0A000 fires if ADD COLUMN carries any
-- constraint (REFERENCES, NOT NULL, DEFAULT with CHECK, …).
-- Every mutation below is therefore a standalone ALTER TABLE.

-- ── Step 1: decompress all compressed chunks before ANY ALTER ──
-- Any ALTER TABLE on a columnstore hypertable requires all chunks
-- to be uncompressed first.
DO $$
DECLARE
    r RECORD;
BEGIN
    PERFORM 1
    FROM pg_catalog.pg_proc
    WHERE proname = 'decompress_chunk'
      AND pg_function_is_visible(oid);

    IF NOT FOUND THEN
        RAISE NOTICE 'timescaledb.decompress_chunk not available; skipping decompression';
        RETURN;
    END IF;

    FOR r IN
        SELECT chunk_schema, chunk_name
        FROM   timescaledb_information.chunks
        WHERE  hypertable_schema = 'public'
          AND  hypertable_name   = 'skin_price_history'
          AND  is_compressed     = true
    LOOP
        EXECUTE format(
            'SELECT decompress_chunk(%I.%I)',
            r.chunk_schema, r.chunk_name
        );
    END LOOP;

    RAISE NOTICE 'All compressed chunks for skin_price_history have been decompressed.';
END;
$$;

-- ── Step 2: add plain NULLable INT columns, NO constraints ────
ALTER TABLE skin_price_history ADD COLUMN IF NOT EXISTS source_id   INT;
ALTER TABLE skin_price_history ADD COLUMN IF NOT EXISTS currency_id INT;

-- ── Step 3: back-fill before adding NOT NULL ──────────────────
-- Use OR so the UPDATE still runs if only one column is NULL.
-- Avoids "WHERE condition is always false" on re-runs after
-- the columns have already been tightened to NOT NULL.
UPDATE skin_price_history
SET source_id   = (SELECT id FROM price_sources WHERE name = 'steam'),
    currency_id = (SELECT id FROM currencies   WHERE code = 'USD')
WHERE source_id IS NULL OR currency_id IS NULL;

-- ── Step 4: add FK constraints as separate ALTER TABLE ────────
ALTER TABLE skin_price_history
    ADD CONSTRAINT fk_sph_source   FOREIGN KEY (source_id)
        REFERENCES price_sources(id);
ALTER TABLE skin_price_history
    ADD CONSTRAINT fk_sph_currency FOREIGN KEY (currency_id)
        REFERENCES currencies(id);

-- ── Step 5: tighten nullability ───────────────────────────────
ALTER TABLE skin_price_history ALTER COLUMN source_id   SET NOT NULL;
ALTER TABLE skin_price_history ALTER COLUMN currency_id SET NOT NULL;

-- ── Step 6: update unique constraint ─────────────────────────
-- TimescaleDB requires the time column (recorded_at) to be part
-- of every UNIQUE constraint on a hypertable.
ALTER TABLE skin_price_history
    DROP CONSTRAINT IF EXISTS skin_price_history_unique;
ALTER TABLE skin_price_history
    ADD CONSTRAINT skin_price_history_unique
        UNIQUE (skin_id, wear_id, source_id, currency_id, recorded_at);

-- ── Step 7: update compression segmentby ─────────────────────
-- Including source_id and currency_id lets the planner skip
-- decompression of unrelated segments — the typical access pattern.
-- Chunks are already fully decompressed from Step 1 above.
ALTER TABLE skin_price_history
    SET (
        timescaledb.compress,
        timescaledb.compress_segmentby = 'skin_id, wear_id, source_id, currency_id',
        timescaledb.compress_orderby   = 'recorded_at DESC'
    );

-- Re-register the compression policy (idempotent via if_not_exists).
-- The previous policy (from migration 003) targets the same interval
-- but was registered under the old segmentby; remove it first.
SELECT remove_compression_policy('skin_price_history', if_not_exists => TRUE);
SELECT add_compression_policy(
    'skin_price_history',
    compress_after => INTERVAL '6 months',
    if_not_exists  => TRUE
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

SELECT constraint_name, table_name
FROM   information_schema.table_constraints
WHERE  table_schema     = 'public'
  AND  constraint_type  IN ('PRIMARY KEY', 'UNIQUE')
  AND  table_name       = 'skin_price_history'
ORDER  BY constraint_type, constraint_name;


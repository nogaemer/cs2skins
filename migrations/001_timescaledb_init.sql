-- ============================================================
-- Migration 001: TimescaleDB Initialization
-- ============================================================
-- Enable the TimescaleDB extension (idempotent).
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- ------------------------------------------------------------
-- skin_price_history
-- ------------------------------------------------------------
-- TimescaleDB requires all PRIMARY KEY / UNIQUE constraints to
-- include the partition (time) column.  The ORM creates the PK
-- as (seq, recorded_at), which already satisfies this rule.
-- If the table was previously created without recorded_at in
-- the PK, recreate it here.

ALTER TABLE skin_price_history
    DROP CONSTRAINT IF EXISTS skin_price_history_pkey;

ALTER TABLE skin_price_history
    ADD PRIMARY KEY (seq, recorded_at);

-- Convert to hypertable partitioned on recorded_at (BIGINT,
-- epoch-milliseconds). chunk_time_interval = 604 800 000 ms
-- (7 days).  if_not_exists prevents errors on re-runs.
SELECT create_hypertable(
    'skin_price_history',
    'recorded_at',
    chunk_time_interval => 604800000::BIGINT,
    if_not_exists       => TRUE
);

-- Register the epoch-ms "now" function so that integer-based
-- compression/retention policies work correctly.
-- This single function is intentionally shared by both hypertables
-- (skin_price_history and tradeup_snapshots) because both use the
-- same BIGINT epoch-millisecond convention for their time dimension.
CREATE OR REPLACE FUNCTION now_ms() RETURNS BIGINT
    LANGUAGE SQL STABLE AS
    $$ SELECT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT $$;

SELECT set_integer_now_func(
    'skin_price_history',
    'now_ms',
    replace_if_exists => TRUE
);

-- ------------------------------------------------------------
-- tradeup_snapshots
-- ------------------------------------------------------------
ALTER TABLE tradeup_snapshots
    DROP CONSTRAINT IF EXISTS tradeup_snapshots_pkey;

ALTER TABLE tradeup_snapshots
    ADD PRIMARY KEY (tradeup_id, snapshot_time, snapshot_seq);

SELECT create_hypertable(
    'tradeup_snapshots',
    'snapshot_time',
    chunk_time_interval => 604800000::BIGINT,
    if_not_exists       => TRUE
);

SELECT set_integer_now_func(
    'tradeup_snapshots',
    'now_ms',
    replace_if_exists => TRUE
);

-- ------------------------------------------------------------
-- Smoke test: confirm both hypertables are registered.
-- Returns one row per confirmed hypertable name; a missing row
-- means create_hypertable() did not run successfully.
-- ------------------------------------------------------------
SELECT hypertable_name
FROM   timescaledb_information.hypertables
WHERE  hypertable_name IN ('skin_price_history', 'tradeup_snapshots')
ORDER  BY hypertable_name;

-- ============================================================
-- Migration 002: Convert time columns to timestamptz
-- ============================================================
-- Converts skin_price_history.recorded_at and
-- tradeup_snapshots.snapshot_time from epoch-ms BIGINT to
-- timestamptz and re-registers both tables as TimescaleDB
-- hypertables with a 7-day INTERVAL chunk.
--
-- Strategy: table recreation (the only safe path for changing
-- a TimescaleDB time-dimension column type).  Existing data is
-- preserved via to_timestamp(col / 1000.0).
--
-- Run AFTER migration 001 has been applied.
-- ============================================================

-- ------------------------------------------------------------
-- skin_price_history
-- ------------------------------------------------------------

-- 1. Create replacement table with timestamptz time column.
CREATE TABLE skin_price_history_new (
    seq         SERIAL,
    skin_id     VARCHAR(255) NOT NULL
                    REFERENCES skins(skin_id)
                    ON DELETE CASCADE ON UPDATE CASCADE,
    wear_id     VARCHAR(255) NOT NULL
                    REFERENCES wear_conditions(wear_id)
                    ON DELETE RESTRICT ON UPDATE CASCADE,
    recorded_at TIMESTAMPTZ  NOT NULL,
    price       NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    quantity    INTEGER       NOT NULL DEFAULT 0,
    PRIMARY KEY (seq, recorded_at)
);

-- 2. Copy existing rows, converting epoch-ms → timestamptz.
INSERT INTO skin_price_history_new
    (seq, skin_id, wear_id, recorded_at, price, quantity)
SELECT seq,
       skin_id,
       wear_id,
       to_timestamp(recorded_at / 1000.0),
       price,
       quantity
FROM   skin_price_history;

-- 3. Drop old hypertable (CASCADE also drops TimescaleDB
--    compression/retention policies, chunks and any integer_now
--    function registration).
DROP TABLE skin_price_history CASCADE;

-- 4. Rename replacement table.
ALTER TABLE skin_price_history_new RENAME TO skin_price_history;

-- 4a. Ensure the seq SERIAL sequence is advanced to the current max.
SELECT setval(
    pg_get_serial_sequence('skin_price_history', 'seq'),
    COALESCE((SELECT MAX(seq) FROM skin_price_history), 0),
    TRUE
);
-- 5. Recreate composite index used by skin+wear range queries.
CREATE INDEX idx_sph_skin_wear ON skin_price_history (skin_id, wear_id);

-- 6. Register as a hypertable partitioned by timestamptz with a
--    7-day chunk interval.  if_not_exists prevents errors on
--    repeated runs.
SELECT create_hypertable(
    'skin_price_history',
    'recorded_at',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists       => TRUE,
    migrate_data        => TRUE
);

-- ------------------------------------------------------------
-- tradeup_snapshots
-- ------------------------------------------------------------

-- 1. Create replacement table with timestamptz time column.
CREATE TABLE tradeup_snapshots_new (
    snapshot_seq  SERIAL,
    tradeup_id    INTEGER NOT NULL
                      REFERENCES tradeups_master(id)
                      ON DELETE CASCADE ON UPDATE CASCADE,
    snapshot_time TIMESTAMPTZ NOT NULL,
    roi           DOUBLE PRECISION NOT NULL DEFAULT 0,
    profit        DOUBLE PRECISION NOT NULL DEFAULT 0,
    input_cost    DOUBLE PRECISION NOT NULL DEFAULT 0,
    output_cost   DOUBLE PRECISION NOT NULL DEFAULT 0,
    PRIMARY KEY (tradeup_id, snapshot_time, snapshot_seq)
);

-- 2. Copy existing rows, converting epoch-ms → timestamptz.
INSERT INTO tradeup_snapshots_new
    (snapshot_seq, tradeup_id, snapshot_time, roi, profit, input_cost, output_cost)
SELECT snapshot_seq,
       tradeup_id,
       to_timestamp(snapshot_time / 1000.0),
       roi,
       profit,
       input_cost,
       output_cost
FROM   tradeup_snapshots;

-- 3. Drop old hypertable.
DROP TABLE tradeup_snapshots CASCADE;

-- 4. Rename replacement table.
ALTER TABLE tradeup_snapshots_new RENAME TO tradeup_snapshots;

-- 4a. Align the sequence backing snapshot_seq with existing data to avoid
--     future key conflicts when relying on the default value.
SELECT setval(
    pg_get_serial_sequence('tradeup_snapshots', 'snapshot_seq'),
    COALESCE((SELECT MAX(snapshot_seq) FROM tradeup_snapshots), 0)
);
-- 5. Recreate index used by time-range queries on the hypertable.
CREATE INDEX idx_ts_snapshot_time ON tradeup_snapshots (snapshot_time);

-- 6. Register as a hypertable.
SELECT create_hypertable(
    'tradeup_snapshots',
    'snapshot_time',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists       => TRUE,
    migrate_data        => TRUE
);

-- ------------------------------------------------------------
-- Smoke test: confirm both hypertables are registered.
-- Returns one row per confirmed hypertable; a missing row
-- indicates that create_hypertable() did not run successfully.
-- ------------------------------------------------------------
SELECT hypertable_name
FROM   timescaledb_information.hypertables
WHERE  hypertable_name IN ('skin_price_history', 'tradeup_snapshots')
ORDER  BY hypertable_name;

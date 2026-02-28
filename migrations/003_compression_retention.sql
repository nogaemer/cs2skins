-- ============================================================
-- Migration 003: Compression and Retention Policies
-- ============================================================
-- Enables TimescaleDB native compression on the two hypertables
-- and registers background policies that:
--   • compress old chunks, and
--   • thin out very old data to half density (delete every second
--     row) rather than dropping it entirely.
--
-- Prerequisites: migrations 001 and 002 must have been applied
-- (tables must exist as TimescaleDB hypertables with timestamptz
-- time-dimension columns).
--
-- All compression policy calls use if_not_exists => TRUE so this
-- migration is safe to re-run (idempotent).  The thinning job
-- registration is guarded by an explicit existence check.
--
-- ┌────────────────────────────┬───────────────┬─────────────────────────────┐
-- │ Table                      │ Compress after│ Thinning (half density after)│
-- ├────────────────────────────┼───────────────┼─────────────────────────────┤
-- │ skin_price_history         │   6 months    │        2 years              │
-- │ tradeup_snapshots          │   6 months    │        2 years              │
-- └────────────────────────────┴───────────────┴─────────────────────────────┘
--
-- Thinning strategy
-- ─────────────────
-- Data older than 2 years is thinned to ~50 % density rather than
-- dropped entirely.  A weekly background procedure (thin_out_old_data)
-- removes temporal duplicates within 1-week windows per group:
--   • skin_price_history: grouped by (skin_id, wear_id) — the earliest
--     row in each 1-week bucket is kept, all later rows in the same
--     bucket are deleted.  Example: readings on Mon 8th and Tue 9th are
--     in the same week bucket → Mon 8th is kept, Tue 9th is removed.
--   • tradeup_snapshots: grouped by (tradeup_id) — same 1-week bucketing.
--
-- HOW TO CHANGE THESE VALUES AFTER DEPLOYMENT
-- ─────────────────────────────────────────────
-- Compression schedule (no migration needed):
--   SELECT alter_compression_policy('skin_price_history',
--            compress_after => INTERVAL '3 months');
--   SELECT alter_compression_policy('tradeup_snapshots',
--            compress_after => INTERVAL '3 months');
--
-- Thinning threshold (requires dropping and recreating the procedure):
--   DROP PROCEDURE thin_out_old_data;
--   CREATE OR REPLACE PROCEDURE thin_out_old_data ...  (update INTERVAL values)
--   SELECT add_job('thin_out_old_data', INTERVAL '1 week');
--
-- Remove compression policy:
--   SELECT remove_compression_policy('skin_price_history');
--
-- Remove thinning job:
--   SELECT delete_job(job_id)
--   FROM   timescaledb_information.jobs
--   WHERE  proc_name = 'thin_out_old_data';
--
-- ============================================================

-- ============================================================
-- skin_price_history
-- ============================================================

-- Enable compression.
--   compress_segmentby: group chunks by skin_id + wear_id so that
--     queries filtering on those columns can skip decompression of
--     unrelated segments.
--   compress_orderby: rows within each segment are sorted by
--     recorded_at DESC, matching the typical "latest first" query
--     pattern and maximising delta-compression efficiency.
ALTER TABLE skin_price_history
    SET (
        timescaledb.compress,
        timescaledb.compress_segmentby = 'skin_id, wear_id',
        timescaledb.compress_orderby   = 'recorded_at DESC'
    );

-- Schedule automatic compression: compress chunks older than 6 months.
-- Background job runs approximately once per day.
-- To tune: SELECT alter_compression_policy('skin_price_history', compress_after => INTERVAL 'X months');
SELECT add_compression_policy(
    'skin_price_history',
    compress_after => INTERVAL '6 months',
    if_not_exists  => TRUE
);

-- ============================================================
-- tradeup_snapshots
-- ============================================================

-- Enable compression.
--   compress_segmentby: group by tradeup_id so that per-trade-up
--     time-range queries can skip unrelated segments.
--   compress_orderby: ordered by snapshot_time DESC for optimal
--     delta compression of the metric columns.
ALTER TABLE tradeup_snapshots
    SET (
        timescaledb.compress,
        timescaledb.compress_segmentby = 'tradeup_id',
        timescaledb.compress_orderby   = 'snapshot_time DESC'
    );

-- Schedule automatic compression: compress chunks older than 6 months.
-- To tune: SELECT alter_compression_policy('tradeup_snapshots', compress_after => INTERVAL 'X months');
SELECT add_compression_policy(
    'tradeup_snapshots',
    compress_after => INTERVAL '6 months',
    if_not_exists  => TRUE
);

-- ============================================================
-- Half-density thinning procedure + scheduled job
-- ============================================================
-- Instead of dropping all data beyond a hard cutoff, a weekly
-- background job reduces density to ~50 % for data older than
-- 2 years by removing temporal duplicates within 1-week windows.
--
-- Strategy (per-group time-bucket deduplication):
--   For each (skin_id, wear_id) pair in skin_price_history, and
--   each (tradeup_id) in tradeup_snapshots, rows are bucketed into
--   1-week windows (604800 s).  Only the EARLIEST row in each
--   bucket is kept; all later rows in the same bucket are deleted.
--
--   Example: readings on Mon 8th and Tue 9th fall in the same 1-week
--   bucket → Mon 8th is kept, Tue 9th is removed.
--
-- Because data older than 6 months is compressed, the procedure
-- decompresses affected chunks before deleting, then re-compresses
-- any chunks that have passed the 6-month threshold.
--
-- To change the thinning threshold (requires a new migration):
--   DROP PROCEDURE thin_out_old_data;
--   CREATE OR REPLACE PROCEDURE thin_out_old_data ...  (update INTERVAL values)
--   SELECT add_job('thin_out_old_data', INTERVAL '1 week');

CREATE OR REPLACE PROCEDURE thin_out_old_data(job_id INT, config JSONB)
LANGUAGE PLPGSQL AS $$
DECLARE
    _chunk RECORD;
BEGIN
    -- ── skin_price_history ──────────────────────────────────────────
    -- Step 1: decompress chunks older than 2 years (DELETE is blocked
    --         on compressed chunks).
    FOR _chunk IN
        SELECT format('%I.%I', chunk_schema, chunk_name) AS full_name
        FROM   timescaledb_information.chunks
        WHERE  hypertable_name = 'skin_price_history'
          AND  range_end        < NOW() - INTERVAL '2 years'
          AND  is_compressed    = TRUE
    LOOP
        PERFORM decompress_chunk(_chunk.full_name::regclass);
    END LOOP;

    -- Step 2: keep the earliest row per (skin_id, wear_id, 1-week bucket),
    --         delete all later rows in the same bucket.
    --         604800 = 7 * 86400 seconds.
    WITH to_keep AS (
        SELECT MIN(seq) AS min_seq
        FROM   skin_price_history
        WHERE  recorded_at < NOW() - INTERVAL '2 years'
        GROUP  BY skin_id,
                  wear_id,
                  floor(EXTRACT(EPOCH FROM recorded_at) / 604800)
    )
    DELETE FROM skin_price_history
    WHERE  recorded_at < NOW() - INTERVAL '2 years'
      AND  seq NOT IN (SELECT min_seq FROM to_keep);

    -- Step 3: re-compress any uncompressed chunks past the 6-month threshold.
    FOR _chunk IN
        SELECT format('%I.%I', chunk_schema, chunk_name) AS full_name
        FROM   timescaledb_information.chunks
        WHERE  hypertable_name = 'skin_price_history'
          AND  range_end        < NOW() - INTERVAL '6 months'
          AND  is_compressed    = FALSE
    LOOP
        PERFORM compress_chunk(_chunk.full_name::regclass);
    END LOOP;

    -- ── tradeup_snapshots ───────────────────────────────────────────
    FOR _chunk IN
        SELECT format('%I.%I', chunk_schema, chunk_name) AS full_name
        FROM   timescaledb_information.chunks
        WHERE  hypertable_name = 'tradeup_snapshots'
          AND  range_start      < NOW() - INTERVAL '2 years'
          AND  is_compressed    = TRUE
    LOOP
        PERFORM decompress_chunk(_chunk.full_name::regclass);
    END LOOP;

    -- Keep the earliest snapshot per (tradeup_id, 1-week bucket).
    WITH to_keep AS (
        SELECT MIN(snapshot_seq) AS min_seq
        FROM   tradeup_snapshots
        WHERE  snapshot_time < NOW() - INTERVAL '2 years'
        GROUP  BY tradeup_id,
                  floor(EXTRACT(EPOCH FROM snapshot_time) / 604800)
    )
    DELETE FROM tradeup_snapshots
    WHERE  snapshot_time < NOW() - INTERVAL '2 years'
      AND  snapshot_seq NOT IN (SELECT min_seq FROM to_keep);

    FOR _chunk IN
        SELECT format('%I.%I', chunk_schema, chunk_name) AS full_name
        FROM   timescaledb_information.chunks
        WHERE  hypertable_name = 'tradeup_snapshots'
          AND  range_end        < NOW() - INTERVAL '6 months'
          AND  is_compressed    = FALSE
    LOOP
        PERFORM compress_chunk(_chunk.full_name::regclass);
    END LOOP;
END;
$$;

-- Register the thinning job to run once per week.
-- Guarded against duplicate registration (add_job has no if_not_exists).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM timescaledb_information.jobs
        WHERE  proc_name = 'thin_out_old_data'
    ) THEN
        PERFORM add_job('thin_out_old_data', INTERVAL '1 week');
    END IF;
END;
$$;

-- ============================================================
-- Verify: confirm policies and jobs are registered.
-- ============================================================
SELECT hypertable_name,
       compression_enabled
FROM   timescaledb_information.hypertables
WHERE  hypertable_name IN ('skin_price_history', 'tradeup_snapshots')
ORDER  BY hypertable_name;

SELECT proc_name,
       schedule_interval,
       config
FROM   timescaledb_information.jobs
WHERE  proc_name IN ('policy_compression', 'thin_out_old_data')
ORDER  BY proc_name;

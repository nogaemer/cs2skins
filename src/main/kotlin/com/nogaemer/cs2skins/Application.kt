package com.nogaemer.cs2skins

import database.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@SpringBootApplication(scanBasePackages = ["com.nogaemer.cs2skins", "database", "tradeup"])
@EnableCaching
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

@Component
class SkinDatabaseInitializer {

    private val logger = LoggerFactory.getLogger(SkinDatabaseInitializer::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun initializeTables() {
        transaction {
            // Reference / master tables
            SchemaUtils.create(
                Collections,
                Weapons,
                Rarities,
                WearConditions,
                Skins
            )

            // Pricing reference tables — must exist before SkinPricesCurrent / SkinPriceHistory
            // because those tables hold FKs to price_sources and currencies.
            SchemaUtils.create(
                PriceSources,
                Currencies
            )
            seedPricingReferenceData()

            // Price tables (current snapshot + history hypertable)
            SchemaUtils.create(
                SkinPricesCurrent,
                SkinPriceHistory
            )

            // Trade-up tables
            SchemaUtils.create(
                OutputPools,
                TradeupsMaster,
                TradeupsCurrent,
                TradeupSnapshots,
                TradeUpInputs,
                OutputPoolItems
            )

            // TimescaleDB setup (hypertables, compression, aggregates, indexes)
            setupTimescaleDB()
        }
    }

    /**
     * Seeds the minimum required rows in `price_sources` and `currencies` so that a fresh
     * database is fully functional without requiring a separate manual migration step.
     * Uses INSERT … IGNORE so subsequent restarts are idempotent.
     */
    private fun Transaction.seedPricingReferenceData() {
        listOf(
            "steam"   to "https://steamcommunity.com/market",
            "csfloat" to "https://csfloat.com",
            "buff163" to "https://buff.163.com"
        ).forEach { (name, url) ->
            PriceSources.insertIgnore {
                it[PriceSources.name]    = name
                it[PriceSources.baseUrl] = url
            }
        }

        listOf(
            Triple("USD", "$",  true),
            Triple("EUR", "€",  false),
            Triple("CNY", "¥",  false)
        ).forEach { (code, symbol, isBase) ->
            Currencies.insertIgnore {
                it[Currencies.code]   = code
                it[Currencies.symbol] = symbol
                it[Currencies.isBase] = isBase
            }
        }
    }

    private fun Transaction.setupTimescaleDB() {
        try {
            // Enable TimescaleDB extension
            exec("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE")

            // ── Hypertables ──────────────────────────────────────────────────────

            // Convert skin_price_history to a hypertable partitioned by recorded_at (timestamptz).
            // chunk_time_interval = 7 days.  if_not_exists prevents errors on re-runs.
            exec(
                """SELECT create_hypertable(
                    'skin_price_history',
                    'recorded_at',
                    if_not_exists => TRUE,
                    chunk_time_interval => INTERVAL '7 days'
                )"""
            )

            // Convert tradeup_snapshots to a hypertable partitioned by snapshot_time (timestamptz).
            exec(
                """SELECT create_hypertable(
                    'tradeup_snapshots',
                    'snapshot_time',
                    if_not_exists => TRUE,
                    chunk_time_interval => INTERVAL '7 days'
                )"""
            )

            // ── Missing columns (idempotent; safe to run on every startup) ───────

            // Risk metric columns on tradeup_snapshots (added in migration 006)
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS prob_profit DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS variance    DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS p05         DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS p50         DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS p95         DOUBLE PRECISION")

            // Financial metric columns on tradeups_current (added in migration 009)
            exec("ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS input_cost_no_drop_change    DOUBLE PRECISION")
            exec("ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS profit_no_drop_change        DOUBLE PRECISION")
            exec("ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS roi_no_drop_change           DOUBLE PRECISION")
            exec("ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS profit_chance               DOUBLE PRECISION")
            exec("ALTER TABLE tradeups_current ADD COLUMN IF NOT EXISTS profit_chance_no_drop_change DOUBLE PRECISION")

            // Financial metric columns on tradeup_snapshots (added in migration 009/010)
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS input_cost_no_drop_change    DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS profit_no_drop_change        DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS roi_no_drop_change           DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS profit_chance               DOUBLE PRECISION")
            exec("ALTER TABLE tradeup_snapshots ADD COLUMN IF NOT EXISTS profit_chance_no_drop_change DOUBLE PRECISION")

            // ── Price history composite index (migration 005) ─────────────────

            exec(
                """CREATE INDEX IF NOT EXISTS idx_sph_lookup
                   ON skin_price_history (skin_id, wear_id, source_id, currency_id, recorded_at DESC)"""
            )

            // ── Compression ──────────────────────────────────────────────────────

            // Enable compression on skin_price_history (compress chunks older than 6 months).
            // compress_segmentby groups chunks by skin+wear so per-skin queries skip
            // unrelated segments; compress_orderby maximises delta-compression efficiency.
            exec(
                """ALTER TABLE skin_price_history
                   SET (timescaledb.compress,
                        timescaledb.compress_segmentby = 'skin_id, wear_id',
                        timescaledb.compress_orderby   = 'recorded_at DESC')"""
            )
            exec(
                """SELECT add_compression_policy(
                    'skin_price_history',
                    compress_after => INTERVAL '6 months',
                    if_not_exists  => TRUE
                )"""
            )

            // Enable compression on tradeup_snapshots (compress chunks older than 6 months).
            exec(
                """ALTER TABLE tradeup_snapshots
                   SET (timescaledb.compress,
                        timescaledb.compress_segmentby = 'tradeup_id',
                        timescaledb.compress_orderby   = 'snapshot_time DESC')"""
            )
            exec(
                """SELECT add_compression_policy(
                    'tradeup_snapshots',
                    compress_after => INTERVAL '6 months',
                    if_not_exists  => TRUE
                )"""
            )

            // ── Half-density thinning procedure ──────────────────────────────────

            // Half-density thinning: instead of deleting all data past a hard cutoff,
            // a weekly background job removes temporal duplicates older than 2 years.
            exec(
                """CREATE OR REPLACE PROCEDURE thin_out_old_data(job_id INT, config JSONB)
                   LANGUAGE PLPGSQL AS ${'$'}body${'$'}
                   DECLARE
                       _chunk RECORD;
                   BEGIN
                       -- skin_price_history: decompress → thin → recompress
                       FOR _chunk IN
                           SELECT format('%I.%I', chunk_schema, chunk_name) AS full_name
                           FROM   timescaledb_information.chunks
                           WHERE  hypertable_name = 'skin_price_history'
                             AND  range_start      < NOW() - INTERVAL '2 years'
                             AND  is_compressed    = TRUE
                       LOOP
                           PERFORM decompress_chunk(_chunk.full_name::regclass);
                       END LOOP;
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
                       FOR _chunk IN
                           SELECT format('%I.%I', chunk_schema, chunk_name) AS full_name
                           FROM   timescaledb_information.chunks
                           WHERE  hypertable_name = 'skin_price_history'
                             AND  range_end        < NOW() - INTERVAL '6 months'
                             AND  is_compressed    = FALSE
                       LOOP
                           PERFORM compress_chunk(_chunk.full_name::regclass);
                       END LOOP;
                       -- tradeup_snapshots: decompress → thin → recompress
                       FOR _chunk IN
                           SELECT format('%I.%I', chunk_schema, chunk_name) AS full_name
                           FROM   timescaledb_information.chunks
                           WHERE  hypertable_name = 'tradeup_snapshots'
                             AND  range_start      < NOW() - INTERVAL '2 years'
                             AND  is_compressed    = TRUE
                       LOOP
                           PERFORM decompress_chunk(_chunk.full_name::regclass);
                       END LOOP;
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
                   ${'$'}body${'$'}"""
            )
            exec(
                """DO ${'$'}outer${'$'}
                   BEGIN
                       IF NOT EXISTS (
                           SELECT 1 FROM timescaledb_information.jobs
                           WHERE  proc_name = 'thin_out_old_data'
                       ) THEN
                           PERFORM add_job('thin_out_old_data', INTERVAL '1 week');
                       END IF;
                   END;
                   ${'$'}outer${'$'}"""
            )

            // ── Continuous aggregates ─────────────────────────────────────────────

            // tradeup_daily: daily buckets of trade-up metrics.
            // Includes all financial metric columns so fresh installs need no migration.
            // If the view already exists (from an earlier migration), it is left in place;
            // apply migration 010 to upgrade an existing view to include the new columns.
            exec(
                """CREATE MATERIALIZED VIEW IF NOT EXISTS tradeup_daily
                   WITH (timescaledb.continuous) AS
                   SELECT
                       time_bucket('1 day', snapshot_time)        AS bucket,
                       tradeup_id,
                       AVG(roi)                                    AS avg_roi,
                       AVG(profit)                                 AS avg_profit,
                       AVG(input_cost)                             AS avg_input_cost,
                       AVG(output_cost)                            AS avg_output_cost,
                       AVG(input_cost_no_drop_change)              AS avg_input_cost_no_drop_change,
                       AVG(profit_no_drop_change)                  AS avg_profit_no_drop_change,
                       AVG(roi_no_drop_change)                     AS avg_roi_no_drop_change,
                       AVG(profit_chance)                          AS avg_profit_chance,
                       AVG(profit_chance_no_drop_change)           AS avg_profit_chance_no_drop_change,
                       COUNT(*)                                    AS samples
                   FROM tradeup_snapshots
                   GROUP BY bucket, tradeup_id
                   WITH NO DATA"""
            )
            exec(
                """SELECT add_continuous_aggregate_policy(
                    'tradeup_daily',
                    start_offset      => INTERVAL '3 days',
                    end_offset        => INTERVAL '1 hour',
                    schedule_interval => INTERVAL '1 hour',
                    if_not_exists     => TRUE
                )"""
            )

            // skin_price_daily: daily buckets of skin prices.
            exec(
                """CREATE MATERIALIZED VIEW IF NOT EXISTS skin_price_daily
                   WITH (timescaledb.continuous) AS
                   SELECT
                       time_bucket('1 day', recorded_at)    AS bucket,
                       skin_id,
                       wear_id,
                       source_id,
                       currency_id,
                       AVG(price)::NUMERIC(10, 2)           AS avg_price,
                       MIN(price)::NUMERIC(10, 2)           AS min_price,
                       MAX(price)::NUMERIC(10, 2)           AS max_price,
                       COUNT(*)                             AS samples
                   FROM skin_price_history
                   GROUP BY bucket, skin_id, wear_id, source_id, currency_id
                   WITH NO DATA"""
            )
            exec(
                """SELECT add_continuous_aggregate_policy(
                    'skin_price_daily',
                    start_offset      => INTERVAL '3 days',
                    end_offset        => INTERVAL '1 hour',
                    schedule_interval => INTERVAL '1 hour',
                    if_not_exists     => TRUE
                )"""
            )

            // ── Indexes for continuous aggregates ────────────────────────────────

            exec("CREATE INDEX IF NOT EXISTS idx_td_tradeup_bucket ON tradeup_daily (tradeup_id, bucket DESC)")
            exec("CREATE INDEX IF NOT EXISTS idx_td_bucket_avg_roi ON tradeup_daily (bucket, avg_roi DESC)")
            exec("CREATE INDEX IF NOT EXISTS idx_td_bucket_avg_profit ON tradeup_daily (bucket, avg_profit DESC)")
            exec("CREATE INDEX IF NOT EXISTS idx_spd_skin_bucket ON skin_price_daily (skin_id, wear_id, bucket DESC)")
            exec("CREATE INDEX IF NOT EXISTS idx_spd_source_currency ON skin_price_daily (source_id, currency_id, bucket DESC)")

            // ── Smoke test: verify both hypertables are registered ───────────────

            val confirmed = mutableListOf<String>()
            exec(
                """SELECT hypertable_name
                   FROM   timescaledb_information.hypertables
                   WHERE  hypertable_name IN ('skin_price_history', 'tradeup_snapshots')
                   ORDER  BY hypertable_name"""
            ) { rs ->
                while (rs.next()) confirmed.add(rs.getString("hypertable_name"))
            }
            check(confirmed.size == 2) {
                "TimescaleDB hypertables not fully initialized – found: $confirmed"
            }
            logger.info("TimescaleDB hypertables confirmed: $confirmed")
        } catch (e: Exception) {
            logger.warn("TimescaleDB setup skipped (extension may not be available): ${e.message}")
        }
    }
}


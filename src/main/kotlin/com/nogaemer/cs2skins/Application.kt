package com.nogaemer.cs2skins

import database.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
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

            // TimescaleDB setup
            setupTimescaleDB()
        }
    }

    private fun Transaction.setupTimescaleDB() {
        try {
            // Enable TimescaleDB extension
            exec("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE")

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

            // Half-density thinning: instead of deleting all data past a hard cutoff,
            // a weekly background job removes temporal duplicates older than 2 years.
            // Data is bucketed into 1-week windows per (skin_id, wear_id); the earliest
            // row in each bucket is kept and any later rows in the same bucket are deleted
            // (e.g. readings on Mon 8th and Tue 9th of the same week are in the same bucket:
            // Mon 8th is kept, Tue 9th is removed).
            // Data older than 6 months is compressed, so the procedure decompresses
            // affected chunks before deleting and re-compresses afterwards.
            // To change the threshold: drop and recreate the procedure with updated intervals.
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
                             AND  range_end        < NOW() - INTERVAL '2 years'
                             AND  is_compressed    = TRUE
                       LOOP
                           PERFORM decompress_chunk(_chunk.full_name::regclass);
                       END LOOP;
                       -- Keep only the earliest row per (skin_id, wear_id, 1-week bucket).
                       -- 604800 = 7 * 86400 seconds.
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
                       -- Keep only the earliest row per (tradeup_id, 1-week bucket).
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

            // Smoke test: verify both hypertables are registered in the catalog.
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


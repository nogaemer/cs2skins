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

    companion object {
        /** One week expressed in epoch-milliseconds; used as the TimescaleDB chunk interval. */
        private const val WEEK_IN_MS = 604_800_000L
        /** One year expressed in epoch-milliseconds; used as the retention period. */
        private const val YEAR_IN_MS = 31_536_000_000L
    }

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
                TradeupsMaster,
                TradeupsCurrent,
                TradeupSnapshots,
                TradeUpInputs,
                TradeupOutputConfigs,
                TradeupsMasterToOutputs
            )

            // TimescaleDB setup
            setupTimescaleDB()
        }
    }

    private fun Transaction.setupTimescaleDB() {
        try {
            // Enable TimescaleDB extension
            exec("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE")

            // SAFETY NOTE: WEEK_IN_MS and YEAR_IN_MS are Kotlin `const val` compile-time Long
            // literals, not user-supplied input. String interpolation here embeds the numeric
            // literal directly into the SQL text, which is safe and equivalent to writing the
            // integer constant inline. Do NOT replace these with variables that could hold
            // user-controlled values.

            // Convert skin_price_history to a hypertable partitioned by recorded_at
            // chunk_time_interval = 604800000 ms (1 week in epoch-milliseconds)
            exec(
                """SELECT create_hypertable(
                    'skin_price_history',
                    'recorded_at',
                    if_not_exists => TRUE,
                    chunk_time_interval => $WEEK_IN_MS
                )"""
            )

            // Convert tradeup_snapshots to a hypertable partitioned by snapshot_time
            exec(
                """SELECT create_hypertable(
                    'tradeup_snapshots',
                    'snapshot_time',
                    if_not_exists => TRUE,
                    chunk_time_interval => $WEEK_IN_MS
                )"""
            )

            // Register an integer_now function (epoch-ms) for hypertables with integer time dim.
            // TimescaleDB requires this before compression/retention policies can use integer durations.
            exec(
                """CREATE OR REPLACE FUNCTION now_ms() RETURNS BIGINT
                   LANGUAGE SQL STABLE AS ${'$'}${'$'} SELECT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT ${'$'}${'$'}"""
            )
            exec("SELECT set_integer_now_func('skin_price_history', 'now_ms', replace_if_exists => TRUE)")
            exec("SELECT set_integer_now_func('tradeup_snapshots', 'now_ms', replace_if_exists => TRUE)")

            // Enable compression on skin_price_history (compress chunks older than 7 days)
            exec(
                """ALTER TABLE skin_price_history
                   SET (timescaledb.compress,
                        timescaledb.compress_segmentby = 'skin_id,wear_id')"""
            )
            exec(
                """SELECT add_compression_policy(
                    'skin_price_history',
                    ${WEEK_IN_MS}::BIGINT,
                    if_not_exists => TRUE
                )"""
            )

            // Enable compression on tradeup_snapshots (compress chunks older than 7 days)
            exec(
                """ALTER TABLE tradeup_snapshots
                   SET (timescaledb.compress,
                        timescaledb.compress_segmentby = 'tradeup_id')"""
            )
            exec(
                """SELECT add_compression_policy(
                    'tradeup_snapshots',
                    ${WEEK_IN_MS}::BIGINT,
                    if_not_exists => TRUE
                )"""
            )

            // Data retention: drop skin price history older than 1 year
            exec(
                """SELECT add_retention_policy(
                    'skin_price_history',
                    ${YEAR_IN_MS}::BIGINT,
                    if_not_exists => TRUE
                )"""
            )

            logger.info("TimescaleDB hypertables, compression and retention policies configured successfully")
        } catch (e: Exception) {
            logger.warn("TimescaleDB setup skipped (extension may not be available): ${e.message}")
        }
    }
}


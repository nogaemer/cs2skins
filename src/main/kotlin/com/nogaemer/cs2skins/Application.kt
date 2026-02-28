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

            // Enable compression on skin_price_history (compress chunks older than 7 days).
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
                    compress_after => INTERVAL '7 days',
                    if_not_exists  => TRUE
                )"""
            )

            // Enable compression on tradeup_snapshots (compress chunks older than 7 days).
            exec(
                """ALTER TABLE tradeup_snapshots
                   SET (timescaledb.compress,
                        timescaledb.compress_segmentby = 'tradeup_id',
                        timescaledb.compress_orderby   = 'snapshot_time DESC')"""
            )
            exec(
                """SELECT add_compression_policy(
                    'tradeup_snapshots',
                    compress_after => INTERVAL '7 days',
                    if_not_exists  => TRUE
                )"""
            )

            // Data retention: drop skin price history older than 90 days.
            // Retention window (90 days) is >= compression window (7 days) as required.
            // To change: SELECT alter_retention_policy('skin_price_history', drop_after => INTERVAL 'X days');
            exec(
                """SELECT add_retention_policy(
                    'skin_price_history',
                    drop_after    => INTERVAL '90 days',
                    if_not_exists => TRUE
                )"""
            )

            // Data retention: drop tradeup snapshot history older than 90 days.
            // To change: SELECT alter_retention_policy('tradeup_snapshots', drop_after => INTERVAL 'X days');
            exec(
                """SELECT add_retention_policy(
                    'tradeup_snapshots',
                    drop_after    => INTERVAL '90 days',
                    if_not_exists => TRUE
                )"""
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


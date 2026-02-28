package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.*
import com.nogaemer.cs2skins.service.TradeUpService.Companion.PRICE_CALC_CHUNK_SIZE
import database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.CollectionWithSkins
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tradeup.TradeUp
import tradeup.TradeUpInput
import tradeup.TradeUpInputComponent
import tradeup.TradeUpOptimizer
import tradeup.computeRiskMetrics
import java.io.StringReader
import java.math.BigDecimal
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.measureTimedValue

@Service
class TradeUpService(
    private val collectionRepository: CollectionRepository,
    private val optimizer: TradeUpOptimizer,
    private val jdbcTemplate: JdbcTemplate
) {

    private val logger = LoggerFactory.getLogger(TradeUpService::class.java)

    // Progress tracking for generate-masters job
    val generateMastersTotal = AtomicLong(0)
    val generateMastersProcessed = AtomicLong(0)

    // Progress tracking for calculate-prices job
    val calculatePricesTotal = AtomicLong(0)
    val calculatePricesProcessed = AtomicLong(0)

    companion object {
        private const val INSERT_CHUNK_SIZE =
            100_000  // Reduced from 10k for better throughput (less memory, shorter locks)
        private const val OUTPUT_FLUSH_SIZE = 100_000  // Stream outputs in smaller chunks to avoid heap pressure
        private const val PRICE_CALC_CHUNK_SIZE = 5_000
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    /**
     * Batch insert output pools using JdbcTemplate COPY FROM STDIN.
     *
     * @param poolsToInsert List of pool rows: [id, hash]
     */
    private suspend fun batchInsertOutputPools(poolsToInsert: List<Array<Any?>>) = withContext(Dispatchers.IO) {
        val tsvData = StringBuilder()
        for (row in poolsToInsert) {
            val id = row[0] as Int
            val hash = row[1] as String
            tsvData.append("$id\t$hash\n")
        }

        jdbcTemplate.execute { conn: Connection ->
            val pgConn = conn.unwrap(BaseConnection::class.java)
            val copyManager = CopyManager(pgConn)

            val sql =
                "COPY output_pools (id, hash) FROM STDIN WITH (FORMAT CSV, DELIMITER '\t')"

            StringReader(tsvData.toString()).use { reader ->
                copyManager.copyIn(sql, reader)
            }
            null
        }
    }

    /**
     * Batch insert output pool items using JdbcTemplate COPY FROM STDIN.
     *
     * @param itemsToInsert List of item rows: [poolId, skinId, probability, floatValue]
     */
    private suspend fun batchInsertOutputPoolItems(itemsToInsert: List<Array<Any?>>) = withContext(Dispatchers.IO) {
        val tsvData = StringBuilder()
        for (row in itemsToInsert) {
            val poolId = row[0] as Int
            val skinId = row[1] as String
            val probability = row[2] as Double
            val floatValue = row[3] as Double
            tsvData.append("$poolId\t$skinId\t$probability\t$floatValue\n")
        }

        jdbcTemplate.execute { conn: Connection ->
            val pgConn = conn.unwrap(BaseConnection::class.java)
            val copyManager = CopyManager(pgConn)

            val sql =
                "COPY output_pool_items (pool_id, skin_id, probability, float_value) FROM STDIN WITH (FORMAT CSV, DELIMITER '\t')"

            StringReader(tsvData.toString()).use { reader ->
                copyManager.copyIn(sql, reader)
            }
            null
        }
    }


    private suspend fun batchInsertMasters(
        mastersToInsert: List<Array<Any?>>
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Build the giant TSV (Tab Separated Values) string
        val tsvData = StringBuilder()
        for (row in mastersToInsert) {
            val colA = row[0] as String
            val colB = row[1] as String
            val rarity = row[2] as String? ?: ""
            val isStattrak = if (row[3] as Boolean) "t" else "f"
            val skinA = row[4] as String
            val skinB = row[5] as String
            val amountA = row[6] as Int
            val amountB = row[7] as Int
            val outputFloat = row[8] as Double
            val id = row[9] as Int
            val outputPoolId = row[10] as Int

            tsvData.append("$id\t$colA\t$colB\t$rarity\t$isStattrak\t$skinA\t$skinB\t$amountA\t$amountB\t$outputFloat\t$now\t$outputPoolId\n")
        }

        // Execute using the explicit ConnectionCallback interface
        jdbcTemplate.execute { conn: Connection ->
            val pgConn = conn.unwrap(BaseConnection::class.java)
            val copyManager = CopyManager(pgConn)

            // Postgres COPY command telling it to expect Tab-delimited CSV data
            val sql =
                "COPY tradeups_master (id, collection_a_id, collection_b_id, rarity_id, stattrak, skin_a_id, skin_b_id, amount_a, amount_b, output_float, created_at, output_pool_id) FROM STDIN WITH (FORMAT CSV, DELIMITER '\t')"

            StringReader(tsvData.toString()).use { reader ->
                copyManager.copyIn(sql, reader)
            }
            null // ConnectionCallback requires a return value
        }
    }

    suspend fun test() {
        val collectionA = optimizer.getCollectionWithSkins(collectionRepository.findById("collection-set-community-12")!!, false)
        val collectionB = optimizer.getCollectionWithSkins(collectionRepository.findById("collection-set-community-22")!!, false)

        val skinsOutputA = collectionA.skins["Classified"].orEmpty()
        val skinsOutputB = collectionB.skins["Classified"].orEmpty()
        val outputSkins = skinsOutputA.union(skinsOutputB.toSet()).toMutableList()
        val outputFloats = optimizer.calculateOutputfloatsDTO(outputSkins)
        println(outputFloats)
    }


    /**
     * Generate master trade-up definitions for all valid combinations.
     * This is idempotent: existing masters are not duplicated.
     * Does NOT compute prices or ROI — run [calculatePricesForMasters] afterwards.
     */
    suspend fun generateMasterDefinitions(stattrak: Boolean = false): Int = withContext(Dispatchers.IO) {
        // 1. Instantly wipe the tables before rebuilding to avoid conflicts
        dbQuery {
            jdbcTemplate.execute("TRUNCATE TABLE output_pool_items, tradeups_master, output_pools RESTART IDENTITY CASCADE")

            // 2. Temporarily drop indexes to massively speed up bulk insertion
            jdbcTemplate.execute(
                """
            ALTER TABLE tradeups_master DROP CONSTRAINT IF EXISTS uniq_tm_identity CASCADE;
            DROP INDEX IF EXISTS idx_tm_stattrak;
            DROP INDEX IF EXISTS idx_tm_rarity;
            DROP INDEX IF EXISTS idx_tm_collections;
            DROP INDEX IF EXISTS idx_tm_skins;
            DROP INDEX IF EXISTS idx_tm_output_pool;
            DROP INDEX IF EXISTS idx_output_pool_items_pool;
        """.trimIndent()
            )
        }

        val collections = collectionRepository.findAll()

        // Compute total pair count for progress tracking
        val totalPairs = collections.size.toLong() * (collections.size + 1) / 2
        generateMastersTotal.set(totalPairs)
        generateMastersProcessed.set(0)

        // Pre-load rarity name→id mapping to avoid repeated per-row queries
        val rarityNameToId: Map<String, String> = dbQuery {
            Rarities.selectAll().associate { it[Rarities.name] to it[Rarities.rarityId] }
        }

        var insertedCount = 0
        var currentIdSequence = 1

        val pendingMasters = mutableListOf<Array<Any?>>()
        val poolHashMap = mutableMapOf<String, Int>()
        val currentPoolIdRef = intArrayOf(1)
        val pendingPools = mutableListOf<Array<Any?>>()
        val pendingPoolItems = mutableListOf<Array<Any?>>()

        for (i in collections.indices) {
            val collectionA = collections[i]
            val collectionWithSkinsA = optimizer.getCollectionWithSkins(collectionA, stattrak)

            for (j in i until collections.size) {
                val collectionB = collections[j]
                val collectionWithSkinsB = optimizer.getCollectionWithSkins(collectionB, stattrak)

                val masters = generateMastersForPair(
                    collectionWithSkinsA,
                    collectionWithSkinsB,
                    rarityNameToId,
                    stattrak,
                    currentIdSequence,
                    poolHashMap,
                    currentPoolIdRef,
                    pendingPools,
                    pendingPoolItems
                )

                pendingMasters.addAll(masters)

                val mastersSize = masters.size
                currentIdSequence += mastersSize
                insertedCount += mastersSize

                // Cross-pair batching!
                // Rule: flush order must be pools → pool items → masters to satisfy FK constraints.
                if (pendingMasters.size >= INSERT_CHUNK_SIZE || pendingPoolItems.size >= OUTPUT_FLUSH_SIZE) {

                    // 1. Always flush Pools first
                    if (pendingPools.isNotEmpty()) {
                        val (_, duration) = measureTimedValue {
                            batchInsertOutputPools(pendingPools)
                        }
                        logger.warn("Flushed ${pendingPools.size} output pools in ${duration.inWholeMilliseconds}ms")
                        pendingPools.clear()
                    }

                    // 2. Then flush Pool Items
                    if (pendingPoolItems.isNotEmpty()) {
                        val (_, duration) = measureTimedValue {
                            batchInsertOutputPoolItems(pendingPoolItems)
                        }
                        logger.warn("Flushed ${pendingPoolItems.size} pool items in ${duration.inWholeMilliseconds}ms")
                        pendingPoolItems.clear()
                    }

                    // 3. Then flush Masters (after pools and items exist)
                    if (pendingMasters.isNotEmpty()) {
                        val (_, duration) = measureTimedValue {
                            batchInsertMasters(pendingMasters)
                        }
                        logger.warn("Flushed ${pendingMasters.size} masters in ${duration.inWholeMilliseconds}ms")
                        pendingMasters.clear()
                    }
                }

                generateMastersProcessed.incrementAndGet()
            }
        }

        // Flush any remainders at the end — order: pools → pool items → masters
        if (pendingPools.isNotEmpty()) {
            val (_, duration) = measureTimedValue {
                batchInsertOutputPools(pendingPools)
            }
            logger.warn("Flushed remaining ${pendingPools.size} output pools in ${duration.inWholeMilliseconds}ms")
        }
        if (pendingPoolItems.isNotEmpty()) {
            batchInsertOutputPoolItems(pendingPoolItems)
        }
        if (pendingMasters.isNotEmpty()) {
            val (_, duration) = measureTimedValue {
                batchInsertMasters(pendingMasters)
            }
            logger.warn("Flushed remaining ${pendingMasters.size} masters in ${duration.inWholeMilliseconds}ms")
        }


        logger.warn("Generation complete. Rebuilding database indexes... This might take a minute or two.")
        dbQuery {
            jdbcTemplate.execute(
                """
                ALTER TABLE tradeups_master ADD CONSTRAINT uniq_tm_identity UNIQUE (collection_a_id, collection_b_id, rarity_id, stattrak, skin_a_id, skin_b_id, amount_a, amount_b, output_float);
                CREATE INDEX IF NOT EXISTS idx_tm_stattrak ON tradeups_master (stattrak);
                CREATE INDEX IF NOT EXISTS idx_tm_rarity ON tradeups_master (rarity_id);
                CREATE INDEX IF NOT EXISTS idx_tm_collections ON tradeups_master (collection_a_id, collection_b_id);
                CREATE INDEX IF NOT EXISTS idx_tm_skins ON tradeups_master (skin_a_id, skin_b_id);
                CREATE INDEX IF NOT EXISTS idx_tm_output_pool ON tradeups_master (output_pool_id);
                CREATE INDEX IF NOT EXISTS idx_output_pool_items_pool ON output_pool_items (pool_id);
            """.trimIndent()
            )
        }
        logger.warn("Indexes rebuilt successfully!")

        dbQuery {
            jdbcTemplate.execute("SELECT setval('tradeups_master_id_seq', $insertedCount)")
            jdbcTemplate.execute("SELECT setval('output_pools_id_seq', ${currentPoolIdRef[0] - 1})")
            jdbcTemplate.execute("SELECT setval('output_pool_items_id_seq', (SELECT COALESCE(MAX(id), 0) FROM output_pool_items))")
        }


        return@withContext insertedCount

    }

    private fun generateMastersForPair(
        collectionA: CollectionWithSkins,
        collectionB: CollectionWithSkins,
        rarityNameToId: Map<String, String>,
        stattrak: Boolean,
        startIdSequence: Int,
        poolHashMap: MutableMap<String, Int>,
        currentPoolIdRef: IntArray,
        pendingPools: MutableList<Array<Any?>>,
        pendingPoolItems: MutableList<Array<Any?>>
    ): List<Array<Any?>> {
        val rarityOrder =
            listOf("Consumer Grade", "Industrial Grade", "Mil-Spec Grade", "Restricted", "Classified", "Covert")

        // Find common rarity tiers between both collections (sorted by tier level)
        val commonRarities = (collectionA.skins.keys intersect collectionB.skins.keys)
            .sortedWith(compareBy { s -> val idx = rarityOrder.indexOf(s); if (idx == -1) Int.MAX_VALUE else idx })
            .toList()
        if (commonRarities.isEmpty()) return emptyList()

        val mastersToInsert = mutableListOf<Array<Any?>>()
        var currentId = startIdSequence


        // Iterate through adjacent rarity tiers (e.g., Industrial → Mil-Spec, Mil-Spec → Restricted)
        for (i in commonRarities.indices) {
            if (i + 1 >= commonRarities.size) continue

            val inputRarityName = commonRarities[i]      // Skins used as input
            val outputRarityName = commonRarities[i + 1] // Possible output skins

            val skinsA = collectionA.skins[inputRarityName].orEmpty()
            val skinsB = collectionB.skins[inputRarityName].orEmpty()
            if (skinsA.isEmpty() || skinsB.isEmpty()) continue

            val skinsOutputA = collectionA.skins[outputRarityName].orEmpty()
            val skinsOutputB = collectionB.skins[outputRarityName].orEmpty()
            val outputSkins = skinsOutputA.union(skinsOutputB.toSet()).toMutableList()
            if (outputSkins.isEmpty()) continue

            val outputFloats = optimizer.calculateOutputfloatsDTO(outputSkins)
            val rarityId = rarityNameToId[inputRarityName]

            // For each possible output float value, generate all combinations
            for (outputFloat in outputFloats) {
                val tradeUpOutput = optimizer.calculateTradeUpOutputDTO(outputSkins, outputFloat)
                val outputsByCollection = tradeUpOutput.skins.groupBy { it.collectionId }

                // Generate tradeups with different A/B ballot distributions (1-9, 2-8, ..., 9-1)
                for (j in 1..9) {
                    // Calculate a unique hash for this mathematical outcome.
                    // Use "|" as delimiter to avoid collisions with IDs that may contain underscores.
                    val poolHash = "${collectionA.collectionId}|${collectionB.collectionId}|${rarityId}|${j}|${outputFloat}"

                    // Only create a new pool entry if this hash hasn't been seen before
                    if (!poolHashMap.containsKey(poolHash)) {
                        val poolId = currentPoolIdRef[0]
                        poolHashMap[poolHash] = poolId
                        pendingPools.add(arrayOf(poolId, poolHash))

                        tradeUpOutput.skins.forEach { outputSkin ->
                            val ballotsFromA = if (collectionA.collectionId == outputSkin.collectionId) j else 0
                            val ballotsFromB = if (collectionB.collectionId == outputSkin.collectionId) (10 - j) else 0
                            val totalBallots = ballotsFromA + ballotsFromB
                            val skinsInCollection = outputsByCollection[outputSkin.collectionId]?.size ?: 1
                            val probability = (totalBallots.toDouble() / 10.0) / skinsInCollection.toDouble()

                            pendingPoolItems.add(
                                arrayOf(poolId, outputSkin.skinId, probability, outputSkin.float ?: 0.0)
                            )
                        }

                        currentPoolIdRef[0]++
                    }

                    val resolvedPoolId = poolHashMap[poolHash]!!

                    skinsA.forEach { skinA ->
                        skinsB.forEach { skinB ->
                            // Add Master with reference to the shared output pool
                            mastersToInsert.add(
                                arrayOf(
                                    collectionA.collectionId,
                                    collectionB.collectionId,
                                    rarityId,
                                    stattrak,
                                    skinA.skinId,
                                    skinB.skinId,
                                    j,
                                    10 - j,
                                    outputFloat,
                                    currentId,
                                    resolvedPoolId
                                )
                            )

                            currentId++
                        }
                    }
                }
            }
        }

        return mastersToInsert
    }


    // ============ PRICE CALCULATION ============

    /**
     * Recalculate prices and ROI for all existing [TradeupsMaster] records.
     * For each master the best input combination is derived from current skin prices,
     * then a new [TradeupSnapshots] row is appended and [TradeupsCurrent] is upserted.
     * Input/output skin details are refreshed as well.
     * Masters are fetched in chunks of [PRICE_CALC_CHUNK_SIZE] to avoid loading millions
     * of rows into memory at once. All DB writes for each chunk are done in a single
     * bulk COPY / batch-upsert operation instead of row-by-row inserts.
     */
    suspend fun calculatePricesForMasters(stattrak: Boolean = false): Int = withContext(Dispatchers.IO) {
        // Initialize progress tracking
        val total = dbQuery {
            TradeupsMaster.selectAll()
                .where { TradeupsMaster.stattrak eq stattrak }
                .count()
        }
        calculatePricesTotal.set(total)
        calculatePricesProcessed.set(0)
        if (total == 0L) return@withContext 0

        val rarityOrder =
            listOf("Consumer Grade", "Industrial Grade", "Mil-Spec Grade", "Restricted", "Classified", "Covert")
        val rarityIdToName: Map<String, String> = dbQuery {
            Rarities.selectAll().associate { it[Rarities.rarityId] to it[Rarities.name] }
        }

        // Cache per-collection skin data to avoid redundant DB round-trips
        val collectionSkinsCache = mutableMapOf<String, models.CollectionWithSkins>()

        suspend fun getOrFetchCollection(collectionId: String): models.CollectionWithSkins? {
            val cached = collectionSkinsCache[collectionId]
            if (cached != null) return cached
            val collection = collectionRepository.findById(collectionId) ?: return null
            val result = optimizer.getCollectionWithSkins(collection, stattrak)
            collectionSkinsCache[collectionId] = result
            return result
        }

        // Cursor-based pagination: process masters in chunks
        var processedCount = 0
        var lastId = 0

        while (true) {
            // Fetch next chunk of masters
            val chunk = fetchMasterChunk(stattrak, lastId)
            if (chunk.isEmpty()) break

            // Calculate metrics for all masters in the chunk (in-memory, no DB writes yet)
            val chunkResults = mutableListOf<MasterPriceResult>()
            for (masterRow in chunk) {
                val result = calculateMasterPrice(
                    masterRow = masterRow,
                    rarityOrder = rarityOrder,
                    rarityIdToName = rarityIdToName,
                    getCollectionFunc = ::getOrFetchCollection
                )
                if (result != null) {
                    chunkResults.add(result)
                }
            }

            // Bulk-persist all results for this chunk in one set of DB operations
            if (chunkResults.isNotEmpty()) {
                val (_, duration) = measureTimedValue { bulkSavePriceData(chunkResults) }
                logger.warn("Bulk saved ${chunkResults.size} masters in ${duration.inWholeMilliseconds}ms")
                // Increment progress only after the flush succeeds
                calculatePricesProcessed.addAndGet(chunkResults.size.toLong())
                processedCount += chunkResults.size
            }

            lastId = chunk.last().id
            if (chunk.size < PRICE_CALC_CHUNK_SIZE) break
        }

        processedCount
    }

    /**
     * Fetch a chunk of masters for price calculation using cursor-based pagination.
     */
    private suspend fun fetchMasterChunk(stattrak: Boolean, lastId: Int): List<MasterChunkRow> {
        val (chunk, duration) = dbQuery {
            measureTimedValue {
                TradeupsMaster.selectAll()
                    .where {
                        (TradeupsMaster.stattrak eq stattrak) and
                                (TradeupsMaster.id greater lastId)
                    }
                    .orderBy(TradeupsMaster.id to SortOrder.ASC)
                    .limit(PRICE_CALC_CHUNK_SIZE)
                    .map { row ->
                        MasterChunkRow(
                            id = row[TradeupsMaster.id],
                            collectionAId = row[TradeupsMaster.collectionAId],
                            collectionBId = row[TradeupsMaster.collectionBId],
                            rarityId = row[TradeupsMaster.rarityId],
                            outputFloat = row[TradeupsMaster.outputFloat],
                            skinAId = row[TradeupsMaster.skinAId],
                            skinBId = row[TradeupsMaster.skinBId],
                            amountA = row[TradeupsMaster.amountA],
                            amountB = row[TradeupsMaster.amountB],
                        )
                    }
            }
        }.let { it.value to it.duration }
        logger.warn("Chunk query took ${duration.inWholeMilliseconds}ms")
        return chunk
    }

    /**
     * Data class for holding a chunk of master data from the database.
     */
    data class MasterChunkRow(
        val id: Int,
        val collectionAId: String,
        val collectionBId: String,
        val rarityId: String?,
        val outputFloat: Double,
        val skinAId: String?,
        val skinBId: String?,
        val amountA: Int?,
        val amountB: Int?
    )

    /**
     * Holds the computed price/ROI result for one master trade-up, ready to be bulk-persisted.
     */
    private data class MasterPriceResult(
        val masterId: Int,
        val now: OffsetDateTime,
        val roiValue: Double,
        val profitValue: Double,
        val inputCostValue: Double,
        val outputCostValue: Double,
        val inputASkinId: String,
        val inputASkinName: String,
        val inputAAmount: Int,
        val inputAFloat: Double,
        val inputAPrice: BigDecimal,
        val inputBSkinId: String,
        val inputBSkinName: String,
        val inputBAmount: Int,
        val inputBFloat: Double,
        val inputBPrice: BigDecimal,
        val probProfit: Double,
        val variance: Double,
        val p05: Double,
        val p50: Double,
        val p95: Double,
    )

    /**
     * Calculate price/ROI data for a single master trade-up without touching the DB.
     * Returns null if the master should be skipped.
     */
    private suspend fun calculateMasterPrice(
        masterRow: MasterChunkRow,
        rarityOrder: List<String>,
        rarityIdToName: Map<String, String>,
        getCollectionFunc: suspend (String) -> models.CollectionWithSkins?
    ): MasterPriceResult? {
        // Validate and extract required fields
        val masterId = masterRow.id
        val rarityId = masterRow.rarityId ?: return null
        val rarityName = rarityIdToName[rarityId] ?: return null
        val rarityIdx = rarityOrder.indexOf(rarityName)
        if (rarityIdx < 0 || rarityIdx + 1 >= rarityOrder.size) return null

        // Fetch collection data with caching
        val collectionWithSkinsA = getCollectionFunc(masterRow.collectionAId) ?: return null
        val collectionWithSkinsB = getCollectionFunc(masterRow.collectionBId) ?: return null

        // Validate input skins exist
        val skinA = collectionWithSkinsA.skins[rarityName].orEmpty().find { it.skinId == masterRow.skinAId }
        val skinB = collectionWithSkinsB.skins[rarityName].orEmpty().find { it.skinId == masterRow.skinBId }
        if (skinA == null || skinB == null) {
            logger.warn("Master $masterId: skin(s) no longer available")
            return null
        }

        // Calculate trade-up metrics
        val tradeUp = calculateTradeUpMetrics(
            masterRow = masterRow,
            rarityOrder = rarityOrder,
            rarityName = rarityName,
            collectionWithSkinsA = collectionWithSkinsA,
            collectionWithSkinsB = collectionWithSkinsB,
            skinA = skinA,
            skinB = skinB
        ) ?: return null

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val riskMetrics = computeRiskMetrics(
            tradeUp,
            tradeUp.inputCostWithDropChange.let { if (it.isFinite()) it else 0.0 }
        )
        return MasterPriceResult(
            masterId = masterId,
            now = now,
            roiValue = tradeUp.roiWithDropChange.let { if (it.isFinite()) it else 0.0 },
            profitValue = tradeUp.profitWithDropChange.let { if (it.isFinite()) it else 0.0 },
            inputCostValue = tradeUp.inputCostWithDropChange.let { if (it.isFinite()) it else 0.0 },
            outputCostValue = tradeUp.expectedReturn.let { if (it.isFinite()) it else 0.0 },
            inputASkinId = tradeUp.input.tradeUpInputComponentA.skin.skinId,
            inputASkinName = tradeUp.input.tradeUpInputComponentA.skin.name,
            inputAAmount = tradeUp.input.tradeUpInputComponentA.amount,
            inputAFloat = tradeUp.input.costsFloatInput?.floatA ?: 0.0,
            inputAPrice = BigDecimal(tradeUp.input.tradeUpInputComponentA.skin.price.values.firstOrNull() ?: 0.0),
            inputBSkinId = tradeUp.input.tradeUpInputComponentB.skin.skinId,
            inputBSkinName = tradeUp.input.tradeUpInputComponentB.skin.name,
            inputBAmount = tradeUp.input.tradeUpInputComponentB.amount,
            inputBFloat = tradeUp.input.costsFloatInput?.floatB ?: 0.0,
            inputBPrice = BigDecimal(tradeUp.input.tradeUpInputComponentB.skin.price.values.firstOrNull() ?: 0.0),
            probProfit = riskMetrics.probProfit,
            variance = riskMetrics.variance,
            p05 = riskMetrics.p05,
            p50 = riskMetrics.p50,
            p95 = riskMetrics.p95,
        )
    }

    /**
     * Bulk-persist all price data for a chunk of masters in a **single JDBC transaction**
     * to guarantee atomicity: if any step fails, the whole chunk is rolled back.
     *
     * Write order (on one connection, auto-commit disabled):
     * 1. DELETE stale tradeup_inputs rows for the chunk (single SQL DELETE).
     * 2. COPY new tradeup_inputs rows (2 per master) via PostgreSQL COPY FROM STDIN.
     * 3. COPY new tradeup_snapshots rows (1 per master) via PostgreSQL COPY FROM STDIN.
     * 4. Batch INSERT … ON CONFLICT DO UPDATE for tradeups_current (1 row per master).
     */
    private suspend fun bulkSavePriceData(results: List<MasterPriceResult>) = withContext(Dispatchers.IO) {
        val masterIds = results.map { it.masterId }

        // Build COPY payloads before opening the connection
        val inputsCsv = buildString {
            for (r in results) {
                append("${r.masterId}\t${r.inputASkinId.csvQuote()}\t${r.inputASkinName.csvQuote()}\t${r.inputAAmount}\t${r.inputAFloat}\t${r.inputAPrice}\n")
                append("${r.masterId}\t${r.inputBSkinId.csvQuote()}\t${r.inputBSkinName.csvQuote()}\t${r.inputBAmount}\t${r.inputBFloat}\t${r.inputBPrice}\n")
            }
        }
        val snapshotsCsv = buildString {
            for (r in results) {
                append("${r.masterId}\t${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(r.now)}\t${r.roiValue}\t${r.profitValue}\t${r.inputCostValue}\t${r.outputCostValue}\t${r.probProfit}\t${r.variance}\t${r.p05}\t${r.p50}\t${r.p95}\n")
            }
        }

        // All 4 write operations share a single connection and a single transaction
        jdbcTemplate.execute { conn: Connection ->
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val pgConn = conn.unwrap(BaseConnection::class.java)
                val copyManager = CopyManager(pgConn)

                // 1. Delete stale inputs for this chunk
                conn.prepareStatement(
                    "DELETE FROM tradeup_inputs WHERE tradeup_result_id = ANY(?)"
                ).use { stmt ->
                    stmt.setArray(1, conn.createArrayOf("INTEGER", masterIds.toTypedArray()))
                    stmt.executeUpdate()
                }

                // 2. COPY tradeup_inputs (2 rows per master: skin A and skin B)
                StringReader(inputsCsv).use {
                    copyManager.copyIn(
                        "COPY tradeup_inputs (tradeup_result_id, skin_id, skin_name, amount, float_value, price_per_unit) FROM STDIN WITH (FORMAT CSV, DELIMITER '\t')",
                        it
                    )
                }

                // 3. COPY tradeup_snapshots (1 row per master)
                StringReader(snapshotsCsv).use {
                    copyManager.copyIn(
                        "COPY tradeup_snapshots (tradeup_id, snapshot_time, roi, profit, input_cost, output_cost, prob_profit, variance, p05, p50, p95) FROM STDIN WITH (FORMAT CSV, DELIMITER '\t')",
                        it
                    )
                }

                // 4. Upsert tradeups_current (1 row per master)
                conn.prepareStatement(
                    """
                    INSERT INTO tradeups_current (tradeup_id, roi, profit, input_cost, output_cost, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tradeup_id) DO UPDATE SET
                        roi = EXCLUDED.roi,
                        profit = EXCLUDED.profit,
                        input_cost = EXCLUDED.input_cost,
                        output_cost = EXCLUDED.output_cost,
                        updated_at = EXCLUDED.updated_at
                    """.trimIndent()
                ).use { stmt ->
                    for (r in results) {
                        stmt.setInt(1, r.masterId)
                        stmt.setDouble(2, r.roiValue)
                        stmt.setDouble(3, r.profitValue)
                        stmt.setDouble(4, r.inputCostValue)
                        stmt.setDouble(5, r.outputCostValue)
                        stmt.setLong(6, r.now.toInstant().toEpochMilli())
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = wasAutoCommit
            }
            null
        }
    }

    /** Wraps a string value in CSV double-quotes, escaping any internal double-quotes as "". */
    private fun String.csvQuote() = "\"${this.replace("\"", "\"\"")}\""

    /**
     * Calculate all metrics (ROI, profit, inputs, outputs) for a trade-up.
     * Returns null if calculation fails.
     */
    private suspend fun calculateTradeUpMetrics(
        masterRow: MasterChunkRow,
        rarityOrder: List<String>,
        rarityName: String,
        collectionWithSkinsA: models.CollectionWithSkins,
        collectionWithSkinsB: models.CollectionWithSkins,
        skinA: SkinDTO,
        skinB: SkinDTO
    ): TradeUp? {
        // Get output rarity tier (next tier up from input)
        val outputRarityName = rarityOrder[rarityOrder.indexOf(rarityName) + 1]

        // Fetch possible output skins
        val skinsOutputA = collectionWithSkinsA.skins[outputRarityName].orEmpty()
        val skinsOutputB = collectionWithSkinsB.skins[outputRarityName].orEmpty()
        val outputSkins = skinsOutputA.union(skinsOutputB.toSet()).toMutableList()
        if (outputSkins.isEmpty()) return null

        // Calculate trade-up output distribution
        val tradeUpOutput = optimizer.calculateTradeUpOutputDTO(outputSkins, masterRow.outputFloat)

        // Build input components
        val componentA = TradeUpInputComponent(
            optimizer.skinDtoToSkin(skinA),
            masterRow.amountA ?: return null,
            collectionWithSkinsA.collectionId
        )
        val componentB = TradeUpInputComponent(
            optimizer.skinDtoToSkin(skinB),
            masterRow.amountB ?: return null,
            collectionWithSkinsB.collectionId
        )

        // Find best float values for input
        val bestInput = TradeUpInput(componentA, componentB)
            .calculateBestFloats(masterRow.outputFloat)
            .values
            .minByOrNull { it.costs } ?: return null

        // Build final trade-up object
        return TradeUp(TradeUpInput(componentA, componentB, bestInput), tradeUpOutput)
    }

    // ============ QUERY METHODS ============

    suspend fun getAllTradeUps(): List<TradeUpResultResponse> = dbQuery {
        // Fetch all tradeups with a left join to include metrics (ROI, profit) if available
        val results = TradeupsMaster
            .join(
                TradeupsCurrent, JoinType.LEFT,
                additionalConstraint = { TradeupsMaster.id eq TradeupsCurrent.tradeupId })
            .selectAll()
            .toList()
        mapToTradeUpResponsesBulk(results)
    }

    suspend fun getTradeUpById(id: Int): TradeUpResultResponse? = dbQuery {
        // Fetch single tradeup by ID with metrics
        val results = TradeupsMaster
            .join(
                TradeupsCurrent, JoinType.LEFT,
                additionalConstraint = { TradeupsMaster.id eq TradeupsCurrent.tradeupId })
            .selectAll()
            .where { TradeupsMaster.id eq id }
            .toList()

        if (results.isEmpty()) null
        else mapToTradeUpResponsesBulk(results).firstOrNull()
    }

    suspend fun filterTradeUps(filter: TradeUpFilterRequest): PageResponse<TradeUpResultResponse> = dbQuery {
        // Build base query with table joins
        var query = TradeupsMaster
            .join(
                TradeupsCurrent, JoinType.LEFT,
                additionalConstraint = { TradeupsMaster.id eq TradeupsCurrent.tradeupId })
            .selectAll()

        // Apply all filter conditions
        query = applyTradeUpFilters(query, filter)

        // Apply sorting
        query = applySorting(query, filter)

        // Count total for pagination
        val totalCount = query.count()

        // Apply pagination
        val pageSize = if (filter.size > 0) filter.size else 20
        val pageNumber = if (filter.page >= 0) filter.page else 0
        val offset = pageNumber * pageSize

        val results = query.limit(pageSize, offset.toLong()).toList()
        val content = mapToTradeUpResponsesBulk(results)
        val totalPages = ((totalCount + pageSize - 1) / pageSize).toInt()

        PageResponse(
            content = content,
            page = pageNumber,
            size = pageSize,
            totalElements = totalCount,
            totalPages = totalPages,
            isFirst = pageNumber == 0,
            isLast = pageNumber >= totalPages - 1,
            hasNext = pageNumber < totalPages - 1,
            hasPrevious = pageNumber > 0
        )
    }

    /**
     * Apply all filter conditions (ROI, profit, rarity, stattrak) to the query.
     */
    private fun applyTradeUpFilters(
        baseQuery: Query,
        filter: TradeUpFilterRequest
    ): Query {
        var query = baseQuery

        // Apply ROI range filter
        filter.minRoi?.let { minRoi ->
            query = query.andWhere { TradeupsCurrent.roi greaterEq minRoi }
        }
        filter.maxRoi?.let { maxRoi ->
            query = query.andWhere { TradeupsCurrent.roi lessEq maxRoi }
        }

        // Apply profit range filter
        filter.minProfit?.let { minProfit ->
            query = query.andWhere { TradeupsCurrent.profit greaterEq minProfit }
        }
        filter.maxProfit?.let { maxProfit ->
            query = query.andWhere { TradeupsCurrent.profit lessEq maxProfit }
        }

        // Apply category filters
        filter.stattrak?.let { stattrak ->
            query = query.andWhere { TradeupsMaster.stattrak eq stattrak }
        }
        filter.rarityId?.let { rarityId ->
            query = query.andWhere { TradeupsMaster.rarityId eq rarityId }
        }

        return query
    }

    /**
     * Apply sorting to the query based on filter parameters.
     * Supports sorting by: ROI, profit, inputCost, or updatedAt.
     */
    private fun applySorting(
        baseQuery: Query,
        filter: TradeUpFilterRequest
    ): Query {
        val isAscending = filter.sortDirection.lowercase() == "asc"

        return when (filter.sortBy.lowercase()) {
            "profit" -> baseQuery.orderBy(TradeupsCurrent.profit to if (isAscending) SortOrder.ASC else SortOrder.DESC)
            "inputcost" -> baseQuery.orderBy(TradeupsCurrent.inputCost to if (isAscending) SortOrder.ASC else SortOrder.DESC)
            "updatedat" -> baseQuery.orderBy(TradeupsCurrent.updatedAt to if (isAscending) SortOrder.ASC else SortOrder.DESC)
            else -> baseQuery.orderBy(TradeupsCurrent.roi to if (isAscending) SortOrder.ASC else SortOrder.DESC)  // Default: ROI
        }
    }

    // In TradeUpService.kt
    suspend fun deleteAllTradeUps(): Int = dbQuery {
        // This instantly empties all tables and resets auto-increment sequences
        jdbcTemplate.execute("TRUNCATE TABLE tradeup_snapshots, tradeups_current, output_pool_items, tradeups_master, tradeup_inputs, output_pools RESTART IDENTITY CASCADE")
        1
    }


    /**
     * Returns aggregated time-series history for a specific trade-up.
     * Rows are fetched with the time filter pushed to the DB, then grouped
     * into buckets in-process. The time window is bounded by the [maxPoints]
     * parameter in the controller, keeping memory use manageable.
     *
     * @param tradeupId  the trade-up master record id
     * @param fromMs     start of time range (epoch milliseconds, inclusive)
     * @param toMs       end of time range (epoch milliseconds, inclusive)
     * @param bucketMs   bucket width in milliseconds (e.g. 86400000 for daily, 604800000 for weekly)
     */
    suspend fun getTradeupHistory(
        tradeupId: Int,
        fromMs: Long,
        toMs: Long,
        bucketMs: Long = 86_400_000L
    ): List<TradeUpHistoryPoint> = dbQuery {
        val fromTs = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromMs), ZoneOffset.UTC)
        val toTs = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(toMs), ZoneOffset.UTC)
        TradeupSnapshots.selectAll()
            .where {
                (TradeupSnapshots.tradeupId eq tradeupId) and
                        (TradeupSnapshots.snapshotTime greaterEq fromTs) and
                        (TradeupSnapshots.snapshotTime lessEq toTs)
            }
            .orderBy(TradeupSnapshots.snapshotTime to SortOrder.ASC)
            .toList()
            .groupBy { row ->
                val epochMs = row[TradeupSnapshots.snapshotTime].toInstant().toEpochMilli()
                (epochMs / bucketMs) * bucketMs
            }
            .map { (bucketStart, rows) ->
                TradeUpHistoryPoint(
                    bucketStart = bucketStart,
                    roi = rows.map { it[TradeupSnapshots.roi] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    profit = rows.map { it[TradeupSnapshots.profit] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    inputCost = rows.map { it[TradeupSnapshots.inputCost] }.takeIf { it.isNotEmpty() }?.average()
                        ?: 0.0,
                    outputCost = rows.map { it[TradeupSnapshots.outputCost] }.takeIf { it.isNotEmpty() }?.average()
                        ?: 0.0,
                    probProfit = rows.mapNotNull { it[TradeupSnapshots.probProfit] }
                        .takeIf { it.isNotEmpty() }?.average(),
                    p50 = rows.mapNotNull { it[TradeupSnapshots.p50] }
                        .takeIf { it.isNotEmpty() }?.average(),
                )
            }
    }

    /**
     * Returns aggregated risk metrics for a specific trade-up over a time window.
     *
     * @param tradeupId  the trade-up master record id
     * @param fromMs     start of time range (epoch milliseconds, inclusive)
     * @param toMs       end of time range (epoch milliseconds, inclusive)
     */
    suspend fun getTradeupRiskSummary(
        tradeupId: Int,
        fromMs: Long,
        toMs: Long,
    ): TradeUpRiskSummaryResponse = dbQuery {
        val fromTs = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromMs), ZoneOffset.UTC)
        val toTs = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(toMs), ZoneOffset.UTC)

        val countExpr = TradeupSnapshots.snapshotSeq.count()
        val probProfitAvgExpr = TradeupSnapshots.probProfit.avg()
        val varianceAvgExpr = TradeupSnapshots.variance.avg()
        val p05MinExpr = TradeupSnapshots.p05.min()
        val p50AvgExpr = TradeupSnapshots.p50.avg()
        val p95MaxExpr = TradeupSnapshots.p95.max()

        val aggregateRow = TradeupSnapshots
            .select(countExpr, probProfitAvgExpr, varianceAvgExpr, p05MinExpr, p50AvgExpr, p95MaxExpr)
            .where {
                (TradeupSnapshots.tradeupId eq tradeupId) and
                        (TradeupSnapshots.snapshotTime greaterEq fromTs) and
                        (TradeupSnapshots.snapshotTime lessEq toTs)
            }
            .singleOrNull()

        val snapshotCount = aggregateRow?.get(countExpr)?.toInt() ?: 0
        TradeUpRiskSummaryResponse(
            tradeupId = tradeupId,
            from = fromMs,
            to = toMs,
            snapshotCount = snapshotCount,
            probProfitAvg = aggregateRow?.get(probProfitAvgExpr)?.toDouble(),
            varianceAvg = aggregateRow?.get(varianceAvgExpr)?.toDouble(),
            p05Min = aggregateRow?.get(p05MinExpr),
            p50Avg = aggregateRow?.get(p50AvgExpr)?.toDouble(),
            p95Max = aggregateRow?.get(p95MaxExpr),
        )
    }

    /**
     * Bulk mapping function that eliminates N+1 queries by fetching all related data in batches.
     * Input rows must come from a join of TradeupsMaster + TradeupsCurrent.
     */
    private fun mapToTradeUpResponsesBulk(results: List<ResultRow>): List<TradeUpResultResponse> {
        if (results.isEmpty()) return emptyList()

        val resultIds = results.map { it[TradeupsMaster.id] }

        // Batch-fetch all related data (prevents N+1 queries)
        val inputsByResultId = fetchInputsByMasterIds(resultIds)
        val outputsByResultId = fetchOutputsByMasterIds(resultIds)
        val collectionsById = fetchCollectionsForMasters(results)
        val raritiesById = fetchRaritiesForMasters(results)

        // Map results using pre-fetched data
        return results.map { row ->
            mapRowToTradeUpResponse(
                row = row,
                inputsByResultId = inputsByResultId,
                outputsByResultId = outputsByResultId,
                collectionsById = collectionsById,
                raritiesById = raritiesById
            )
        }
    }

    /**
     * Fetch all input skins for the given master IDs in a single query.
     */
    private fun fetchInputsByMasterIds(resultIds: List<Int>): Map<Int, List<TradeUpInputInfo>> {
        return TradeUpInputs.selectAll()
            .where { TradeUpInputs.tradeUpResultId inList resultIds }
            .map { row ->
                row[TradeUpInputs.tradeUpResultId] to TradeUpInputInfo(
                    skinId = row[TradeUpInputs.skinId],
                    skinName = row[TradeUpInputs.skinName],
                    amount = row[TradeUpInputs.amount],
                    floatValue = row[TradeUpInputs.floatValue],
                    pricePerUnit = row[TradeUpInputs.pricePerUnit]
                )
            }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Fetch all output skins for the given master IDs in a single query.
     * Joins through TradeupsMaster → OutputPoolItems → Skins.
     */
    private fun fetchOutputsByMasterIds(resultIds: List<Int>): Map<Int, List<TradeUpOutputInfo>> {
        return (TradeupsMaster
            .join(OutputPoolItems, JoinType.INNER,
                additionalConstraint = { TradeupsMaster.outputPoolId eq OutputPoolItems.poolId })
            .join(Skins, JoinType.INNER,
                additionalConstraint = { OutputPoolItems.skinId eq Skins.skinId }))
            .selectAll()
            .where { TradeupsMaster.id inList resultIds }
            .map { row ->
                row[TradeupsMaster.id] to TradeUpOutputInfo(
                    skinId = row[OutputPoolItems.skinId],
                    skinName = row[Skins.name],
                    probability = row[OutputPoolItems.probability],
                    floatValue = row[OutputPoolItems.floatValue]
                )
            }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Fetch all collections referenced in the trade-up results in a single query.
     */
    private fun fetchCollectionsForMasters(results: List<ResultRow>): Map<String, CollectionInfo> {
        val collectionIds = results.flatMap {
            listOf(it[TradeupsMaster.collectionAId], it[TradeupsMaster.collectionBId])
        }.distinct()

        return Collections.selectAll()
            .where { Collections.collectionId inList collectionIds }
            .associate {
                it[Collections.collectionId] to CollectionInfo(
                    it[Collections.collectionId],
                    it[Collections.name]
                )
            }
    }

    /**
     * Fetch all rarities referenced in the trade-up results in a single query.
     */
    private fun fetchRaritiesForMasters(results: List<ResultRow>): Map<String, RarityInfo> {
        val rarityIds = results.mapNotNull { it[TradeupsMaster.rarityId] }.distinct()

        if (rarityIds.isEmpty()) return emptyMap()

        return Rarities.selectAll()
            .where { Rarities.rarityId inList rarityIds }
            .associate {
                it[Rarities.rarityId] to RarityInfo(
                    it[Rarities.rarityId],
                    it[Rarities.name],
                    it[Rarities.colorHex]
                )
            }
    }

    /**
     * Map a single database row to a TradeUpResultResponse using pre-fetched data.
     */
    private fun mapRowToTradeUpResponse(
        row: ResultRow,
        inputsByResultId: Map<Int, List<TradeUpInputInfo>>,
        outputsByResultId: Map<Int, List<TradeUpOutputInfo>>,
        collectionsById: Map<String, CollectionInfo>,
        raritiesById: Map<String, RarityInfo>
    ): TradeUpResultResponse {
        val resultId = row[TradeupsMaster.id]
        val collectionAId = row[TradeupsMaster.collectionAId]
        val collectionBId = row[TradeupsMaster.collectionBId]
        val rarityId = row[TradeupsMaster.rarityId]

        // Lookup collections (fallback to ID if not found)
        val collectionA = collectionsById[collectionAId]
            ?: CollectionInfo(collectionAId, collectionAId)
        val collectionB = collectionsById[collectionBId]
            ?: CollectionInfo(collectionBId, collectionBId)

        return TradeUpResultResponse(
            id = resultId,
            collectionA = collectionA,
            collectionB = collectionB,
            rarity = rarityId?.let { raritiesById[it] },
            stattrak = row[TradeupsMaster.stattrak],
            outputFloat = row[TradeupsMaster.outputFloat],
            roi = row.getOrNull(TradeupsCurrent.roi) ?: 0.0,
            profit = row.getOrNull(TradeupsCurrent.profit) ?: 0.0,
            inputCost = row.getOrNull(TradeupsCurrent.inputCost) ?: 0.0,
            outputCost = row.getOrNull(TradeupsCurrent.outputCost) ?: 0.0,
            inputs = inputsByResultId[resultId] ?: emptyList(),
            outputs = outputsByResultId[resultId] ?: emptyList(),
            createdAt = row[TradeupsMaster.createdAt]
        )
    }
}



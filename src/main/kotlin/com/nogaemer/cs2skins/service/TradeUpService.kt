package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.*
import com.nogaemer.cs2skins.service.TradeUpService.Companion.PRICE_CALC_CHUNK_SIZE
import database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.CollectionWithSkins
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
import java.io.StringReader
import java.math.BigDecimal
import java.sql.Connection
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
            """.trimIndent()
            )
        }
        logger.warn("Indexes rebuilt successfully!")

        dbQuery {
            jdbcTemplate.execute("SELECT setval('tradeups_master_id_seq', $insertedCount)")
            jdbcTemplate.execute("SELECT setval('output_pools_id_seq', ${currentPoolIdRef[0] - 1})")
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
                    // Calculate a unique hash for this mathematical outcome
                    val poolHash = "${collectionA.collectionId}_${collectionB.collectionId}_${rarityId}_${j}_${outputFloat}"

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
     * of rows into memory at once.
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

            // Process each master in the chunk
            for (masterRow in chunk) {
                processedCount += calculateAndSaveMasterPrice(
                    masterRow = masterRow,
                    rarityOrder = rarityOrder,
                    rarityIdToName = rarityIdToName,
                    getCollectionFunc = ::getOrFetchCollection
                )
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
     * Calculate and persist price/ROI data for a single master trade-up.
     * Returns 1 if successful, 0 if skipped due to validation.
     */
    private suspend fun calculateAndSaveMasterPrice(
        masterRow: MasterChunkRow,
        rarityOrder: List<String>,
        rarityIdToName: Map<String, String>,
        getCollectionFunc: suspend (String) -> models.CollectionWithSkins?
    ): Int {
        // Validate and extract required fields
        val masterId = masterRow.id
        val rarityId = masterRow.rarityId ?: return 0
        val rarityName = rarityIdToName[rarityId] ?: return 0
        val rarityIdx = rarityOrder.indexOf(rarityName)
        if (rarityIdx < 0 || rarityIdx + 1 >= rarityOrder.size) return 0

        // Fetch collection data with caching
        val collectionWithSkinsA = getCollectionFunc(masterRow.collectionAId) ?: return 0
        val collectionWithSkinsB = getCollectionFunc(masterRow.collectionBId) ?: return 0

        // Validate input skins exist
        val skinA = collectionWithSkinsA.skins[rarityName].orEmpty().find { it.skinId == masterRow.skinAId }
        val skinB = collectionWithSkinsB.skins[rarityName].orEmpty().find { it.skinId == masterRow.skinBId }
        if (skinA == null || skinB == null) {
            logger.warn("Master $masterId: skin(s) no longer available")
            return 0
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
        ) ?: return 0

        // Persist calculated data to database
        saveMasterPriceData(masterId, tradeUp)

        calculatePricesProcessed.incrementAndGet()
        return 1
    }

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

    /**
     * Insert/update all trade-up related data in database:
     * - Input skin details
     * - Output skin details with probabilities (via junction table)
     * - Price snapshot
     * - Current metrics (for fast queries)
     */
    private suspend fun saveMasterPriceData(masterId: Int, tradeUp: TradeUp) {
        val (_, duration) = dbQuery {
            measureTimedValue {
                val now = System.currentTimeMillis()

                // Extract and normalize metrics (handle NaN/Infinity)
                val roiValue = tradeUp.roiWithDropChange.let { if (it.isFinite()) it else 0.0 }
                val profitValue = tradeUp.profitWithDropChange.let { if (it.isFinite()) it else 0.0 }
                val inputCostValue = tradeUp.inputCostWithDropChange.let { if (it.isFinite()) it else 0.0 }
                val outputCostValue = tradeUp.expectedReturn.let { if (it.isFinite()) it else 0.0 }

                // Clear old data
                TradeUpInputs.deleteWhere { TradeUpInputs.tradeUpResultId eq masterId }

                // Insert input skin A
                TradeUpInputs.insert {
                    it[tradeUpResultId] = masterId
                    it[skinId] = tradeUp.input.tradeUpInputComponentA.skin.skinId
                    it[skinName] = tradeUp.input.tradeUpInputComponentA.skin.name
                    it[amount] = tradeUp.input.tradeUpInputComponentA.amount
                    it[floatValue] = tradeUp.input.costsFloatInput?.floatA ?: 0.0
                    it[pricePerUnit] =
                        BigDecimal(tradeUp.input.tradeUpInputComponentA.skin.price.values.firstOrNull() ?: 0.0)
                }

                // Insert input skin B
                TradeUpInputs.insert {
                    it[tradeUpResultId] = masterId
                    it[skinId] = tradeUp.input.tradeUpInputComponentB.skin.skinId
                    it[skinName] = tradeUp.input.tradeUpInputComponentB.skin.name
                    it[amount] = tradeUp.input.tradeUpInputComponentB.amount
                    it[floatValue] = tradeUp.input.costsFloatInput?.floatB ?: 0.0
                    it[pricePerUnit] =
                        BigDecimal(tradeUp.input.tradeUpInputComponentB.skin.price.values.firstOrNull() ?: 0.0)
                }

                // Output skins are managed by the shared OutputPool; no per-master insertion needed.

                // Record snapshot for time-series analysis
                TradeupSnapshots.insert {
                    it[tradeupId] = masterId
                    it[snapshotTime] = now
                    it[roi] = roiValue
                    it[profit] = profitValue
                    it[inputCost] = inputCostValue
                    it[outputCost] = outputCostValue
                }

                // Update current metrics for fast queries
                TradeupsCurrent.upsert(keys = arrayOf(TradeupsCurrent.tradeupId)) {
                    it[tradeupId] = masterId
                    it[roi] = roiValue
                    it[profit] = profitValue
                    it[inputCost] = inputCostValue
                    it[outputCost] = outputCostValue
                    it[updatedAt] = now
                }
            }
        }.let { it.value to it.duration }
        logger.warn("Master $masterId price calculation and save took ${duration.inWholeMilliseconds}ms")
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
        TradeupSnapshots.selectAll()
            .where {
                (TradeupSnapshots.tradeupId eq tradeupId) and
                        (TradeupSnapshots.snapshotTime greaterEq fromMs) and
                        (TradeupSnapshots.snapshotTime lessEq toMs)
            }
            .orderBy(TradeupSnapshots.snapshotTime to SortOrder.ASC)
            .toList()
            .groupBy { row -> (row[TradeupSnapshots.snapshotTime] / bucketMs) * bucketMs }
            .map { (bucketStart, rows) ->
                TradeUpHistoryPoint(
                    bucketStart = bucketStart,
                    roi = rows.map { it[TradeupSnapshots.roi] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    profit = rows.map { it[TradeupSnapshots.profit] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    inputCost = rows.map { it[TradeupSnapshots.inputCost] }.takeIf { it.isNotEmpty() }?.average()
                        ?: 0.0,
                    outputCost = rows.map { it[TradeupSnapshots.outputCost] }.takeIf { it.isNotEmpty() }?.average()
                        ?: 0.0
                )
            }
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



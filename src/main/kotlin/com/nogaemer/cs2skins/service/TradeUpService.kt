package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.*
import com.nogaemer.cs2skins.service.TradeUpService.Companion.PRICE_CALC_CHUNK_SIZE
import database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tradeup.TradeUp
import tradeup.TradeUpInput
import tradeup.TradeUpInputComponent
import tradeup.TradeUpOptimizer
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.measureTimedValue

@Service
class TradeUpService(
    private val collectionRepository: CollectionRepository,
    private val optimizer: TradeUpOptimizer
) {

    private val logger = LoggerFactory.getLogger(TradeUpService::class.java)

    // Progress tracking for generate-masters job
    val generateMastersTotal = AtomicLong(0)
    val generateMastersProcessed = AtomicLong(0)

    // Progress tracking for calculate-prices job
    val calculatePricesTotal = AtomicLong(0)
    val calculatePricesProcessed = AtomicLong(0)

    companion object {
        // Profitability thresholds for saving trade-ups
        private const val MIN_ROI_THRESHOLD = 1.1  // Minimum 10% return on investment
        private const val MAX_INPUT_COST = 10.0    // Maximum input cost in currency units
        private const val MIN_PROFIT_THRESHOLD = 0.10  // Minimum profit in currency units
        private const val MAX_OUTPUT_FLOAT = 0.4   // Maximum acceptable output float value
        private const val INSERT_CHUNK_SIZE = 100000
        private const val PRICE_CALC_CHUNK_SIZE = 5000
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    /**
     * Generate master trade-up definitions for all valid combinations.
     * This is idempotent: existing masters are not duplicated.
     * Does NOT compute prices or ROI — run [calculatePricesForMasters] afterwards.
     */
    suspend fun generateMasterDefinitions(stattrak: Boolean = false): Int = withContext(Dispatchers.IO) {
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

        for (i in collections.indices) {
            val collectionA = collections[i]
            val collectionWithSkinsA = optimizer.getCollectionWithSkins(collectionA, stattrak)

            for (j in i until collections.size) {
                val collectionB = collections[j]
                val collectionWithSkinsB = optimizer.getCollectionWithSkins(collectionB, stattrak)


                val (_, duration) = measureTimedValue {
                    insertedCount += generateMastersForPair(
                        collectionWithSkinsA,
                        collectionWithSkinsB,
                        rarityNameToId,
                        stattrak
                    )
                }
                logger.warn("Processed pair ${collectionA.name} + ${collectionB.name} (inserted $insertedCount masters so far) — took ${duration.inWholeMilliseconds}ms")

                generateMastersProcessed.incrementAndGet()
            }
        }

        insertedCount
    }

    private suspend fun generateMastersForPair(
        collectionA: models.CollectionWithSkins,
        collectionB: models.CollectionWithSkins,
        rarityNameToId: Map<String, String>,
        stattrak: Boolean
    ): Int {
        val rarityOrder = listOf("Consumer Grade", "Industrial Grade", "Mil-Spec Grade", "Restricted", "Classified", "Covert")
        val commonRarities = (collectionA.skins.keys intersect collectionB.skins.keys)
            .sortedWith(compareBy { s -> val idx = rarityOrder.indexOf(s); if (idx == -1) Int.MAX_VALUE else idx })
            .toList()
        if (commonRarities.isEmpty()) return 0

        // Fetch all existing masters for this collection pair in one query to avoid per-row round-trips
        // Key: (rarityId, skinAId, skinBId, amountA, outputFloat)
        val existingKeys: Set<String> = dbQuery {
            TradeupsMaster.selectAll()
                .where {
                    (TradeupsMaster.collectionAId eq collectionA.collectionId) and
                    (TradeupsMaster.collectionBId eq collectionB.collectionId) and
                    (TradeupsMaster.stattrak eq stattrak)
                }
                .map { row ->
                    masterKey(
                        row[TradeupsMaster.rarityId],
                        row[TradeupsMaster.skinAId],
                        row[TradeupsMaster.skinBId],
                        row[TradeupsMaster.amountA],
                        row[TradeupsMaster.outputFloat]
                    )
                }
                .toHashSet()
        }

        var insertedCount = 0
        val toInsert = mutableListOf<Array<Any?>>() // collect rows to batch-insert

        for (i in commonRarities.indices) {
            if (i + 1 >= commonRarities.size) continue

            val inputRarityName = commonRarities[i]
            val outputRarityName = commonRarities[i + 1]

            val skinsA = collectionA.skins[inputRarityName].orEmpty()
            val skinsB = collectionB.skins[inputRarityName].orEmpty()
            if (skinsA.isEmpty() || skinsB.isEmpty()) continue

            val skinsOutputA = collectionA.skins[outputRarityName].orEmpty()
            val skinsOutputB = collectionB.skins[outputRarityName].orEmpty()
            val outputSkins = skinsOutputA.union(skinsOutputB.toSet()).toMutableList()
            if (outputSkins.isEmpty()) continue

            val outputFloats = optimizer.calculateOutputfloatsDTO(outputSkins)
            val rarityId = rarityNameToId[inputRarityName]

            for (outputFloat in outputFloats) {
                for (j in 1..9) {
                    skinsA.forEach { skinA ->
                        skinsB.forEach { skinB ->
                            val key = masterKey(rarityId, skinA.skinId, skinB.skinId, j, outputFloat)
                            if (key !in existingKeys) {
                                toInsert.add(arrayOf(
                                    collectionA.collectionId, collectionB.collectionId,
                                    rarityId, stattrak,
                                    skinA.skinId, skinB.skinId,
                                    j, 10 - j,
                                    outputFloat
                                ))
                            }
                        }
                    }
                }
            }
        }

        if (toInsert.isNotEmpty()) {
            val (_, duration) = dbQuery {
                measureTimedValue {
                    toInsert.chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
                        TradeupsMaster.batchInsert(chunk) { row ->
                            this[TradeupsMaster.collectionAId] = row[0] as String
                            this[TradeupsMaster.collectionBId] = row[1] as String
                            this[TradeupsMaster.rarityId] = row[2] as String?
                            this[TradeupsMaster.stattrak] = row[3] as Boolean
                            this[TradeupsMaster.skinAId] = row[4] as String
                            this[TradeupsMaster.skinBId] = row[5] as String
                            this[TradeupsMaster.amountA] = row[6] as Int
                            this[TradeupsMaster.amountB] = row[7] as Int
                            this[TradeupsMaster.outputFloat] = row[8] as Double
                        }
                    }
                }
            }.let { it.value to it.duration }
            logger.warn("Batch insert of ${toInsert.size} rows took ${duration.inWholeMilliseconds}ms")
            insertedCount = toInsert.size
        }

        return insertedCount
    }

    private fun masterKey(rarityId: String?, skinAId: String?, skinBId: String?, amountA: Int?, outputFloat: Double) =
        "$rarityId|$skinAId|$skinBId|$amountA|$outputFloat"


    /**
     * Recalculate prices and ROI for all existing [TradeupsMaster] records.
     * For each master the best input combination is derived from current skin prices,
     * then a new [TradeupSnapshots] row is appended and [TradeupsCurrent] is upserted.
     * Input/output skin details are refreshed as well.
     * Masters are fetched in chunks of [PRICE_CALC_CHUNK_SIZE] to avoid loading millions
     * of rows into memory at once.
     */
    suspend fun calculatePricesForMasters(stattrak: Boolean = false): Int = withContext(Dispatchers.IO) {
        // Count total masters for progress tracking
        val total = dbQuery {
            TradeupsMaster.selectAll()
                .where { TradeupsMaster.stattrak eq stattrak }
                .count()
        }
        calculatePricesTotal.set(total)
        calculatePricesProcessed.set(0)
        if (total == 0L) return@withContext 0

        val rarityOrder = listOf("Consumer Grade", "Industrial Grade", "Mil-Spec Grade", "Restricted", "Classified", "Covert")
        val rarityIdToName: Map<String, String> = dbQuery {
            Rarities.selectAll().associate { it[Rarities.rarityId] to it[Rarities.name] }
        }

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

        var processedCount = 0
        var lastId = 0

        // Cursor-based pagination: fetch PRICE_CALC_CHUNK_SIZE rows at a time
        while (true) {

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

            if (chunk.isEmpty()) break

            for (masterRow in chunk) {
                val masterId = masterRow.id
                val collectionAId = masterRow.collectionAId
                val collectionBId = masterRow.collectionBId
                val rarityId = masterRow.rarityId ?: continue
                val targetOutputFloat = masterRow.outputFloat
                val masterSkinAId = masterRow.skinAId ?: continue
                val masterSkinBId = masterRow.skinBId ?: continue
                val masterAmountA = masterRow.amountA ?: continue
                val masterAmountB = masterRow.amountB ?: continue

                val rarityName = rarityIdToName[rarityId] ?: continue
                val rarityIdx = rarityOrder.indexOf(rarityName)
                if (rarityIdx < 0 || rarityIdx + 1 >= rarityOrder.size) continue
                val outputRarityName = rarityOrder[rarityIdx + 1]

                val collectionWithSkinsA = getOrFetchCollection(collectionAId) ?: continue
                val collectionWithSkinsB = getOrFetchCollection(collectionBId) ?: continue

                val skinsOutputA = collectionWithSkinsA.skins[outputRarityName].orEmpty()
                val skinsOutputB = collectionWithSkinsB.skins[outputRarityName].orEmpty()

                val outputSkins = skinsOutputA.union(skinsOutputB.toSet()).toMutableList()
                if (outputSkins.isEmpty()) continue

                val tradeUpOutput = optimizer.calculateTradeUpOutputDTO(outputSkins, targetOutputFloat)

                // Use the specific skins and amounts stored in this master record
                val skinA = collectionWithSkinsA.skins[rarityName].orEmpty().find { it.skinId == masterSkinAId }
                val skinB = collectionWithSkinsB.skins[rarityName].orEmpty().find { it.skinId == masterSkinBId }
                if (skinA == null || skinB == null) {
                    logger.warn("Master $masterId: skin(s) no longer available (skinA=$masterSkinAId, skinB=$masterSkinBId) — skipping price calculation")
                    continue
                }

                val componentA = TradeUpInputComponent(optimizer.skinDtoToSkin(skinA), masterAmountA, collectionAId)
                val componentB = TradeUpInputComponent(optimizer.skinDtoToSkin(skinB), masterAmountB, collectionBId)

                val bestInput = TradeUpInput(componentA, componentB)
                    .calculateBestFloats(targetOutputFloat).values.minByOrNull { it.costs }
                    ?: continue

                val tradeUp = TradeUp(TradeUpInput(componentA, componentB, bestInput), tradeUpOutput)
                val roiValue = tradeUp.roiWithDropChange.let { if (it.isFinite()) it else 0.0 }
                val profitValue = tradeUp.profitWithDropChange.let { if (it.isFinite()) it else 0.0 }
                val inputCostValue = tradeUp.inputCostWithDropChange.let { if (it.isFinite()) it else 0.0 }
                val outputCostValue = tradeUp.expectedReturn.let { if (it.isFinite()) it else 0.0 }
                val now = System.currentTimeMillis()

                val (_, duration) = dbQuery {
                    measureTimedValue {
                        // Refresh input/output skin details with current prices
                        TradeUpInputs.deleteWhere { TradeUpInputs.tradeUpResultId eq masterId }
                        TradeUpOutputs.deleteWhere { TradeUpOutputs.tradeUpResultId eq masterId }

                        TradeUpInputs.insert {
                            it[tradeUpResultId] = masterId
                            it[skinId] = tradeUp.input.tradeUpInputComponentA.skin.skinId
                            it[skinName] = tradeUp.input.tradeUpInputComponentA.skin.name
                            it[amount] = tradeUp.input.tradeUpInputComponentA.amount
                            it[floatValue] = tradeUp.input.costsFloatInput?.floatA ?: 0.0
                            it[pricePerUnit] =
                                BigDecimal(tradeUp.input.tradeUpInputComponentA.skin.price.values.firstOrNull() ?: 0.0)
                        }

                        TradeUpInputs.insert {
                            it[tradeUpResultId] = masterId
                            it[skinId] = tradeUp.input.tradeUpInputComponentB.skin.skinId
                            it[skinName] = tradeUp.input.tradeUpInputComponentB.skin.name
                            it[amount] = tradeUp.input.tradeUpInputComponentB.amount
                            it[floatValue] = tradeUp.input.costsFloatInput?.floatB ?: 0.0
                            it[pricePerUnit] =
                                BigDecimal(tradeUp.input.tradeUpInputComponentB.skin.price.values.firstOrNull() ?: 0.0)
                        }

                        val outputsByCollection = tradeUp.output.skins.groupBy { it.collectionId }
                        tradeUp.output.skins.forEach { outputSkin ->
                            val ballotsFromA =
                                if (tradeUp.input.tradeUpInputComponentA.collectionId == outputSkin.collectionId)
                                    tradeUp.input.tradeUpInputComponentA.amount else 0
                            val ballotsFromB =
                                if (tradeUp.input.tradeUpInputComponentB.collectionId == outputSkin.collectionId)
                                    tradeUp.input.tradeUpInputComponentB.amount else 0
                            val totalBallotsForCollection = ballotsFromA + ballotsFromB
                            val skinsInCollection = outputsByCollection[outputSkin.collectionId]?.size ?: 1
                            val probability =
                                (totalBallotsForCollection.toDouble() / 10.0) / skinsInCollection.toDouble()

                            TradeUpOutputs.insert {
                                it[tradeUpResultId] = masterId
                                it[skinId] = outputSkin.skinId
                                it[skinName] = outputSkin.name
                                it[TradeUpOutputs.probability] = probability
                                it[floatValue] = outputSkin.float ?: 0.0
                                it[price] = BigDecimal(outputSkin.price.values.firstOrNull() ?: 0.0)
                            }
                        }

                        TradeupSnapshots.insert {
                            it[tradeupId] = masterId
                            it[snapshotTime] = now
                            it[roi] = roiValue
                            it[profit] = profitValue
                            it[inputCost] = inputCostValue
                            it[outputCost] = outputCostValue
                        }

                        TradeupsCurrent.upsert(keys = arrayOf(TradeupsCurrent.tradeupId)) {
                            it[tradeupId] = masterId
                            it[roi] = roiValue
                            it[profit] = profitValue
                            it[inputCost] = inputCostValue
                            it[outputCost] = outputCostValue
                            it[updatedAt] = now
                        }

                        processedCount++
                        calculatePricesProcessed.incrementAndGet()
                    }
                }.let { it.value to it.duration }
                logger.warn("Master $masterId price calculation and save took ${duration.inWholeMilliseconds}ms")

                lastId = chunk.last().id
                if (chunk.size < PRICE_CALC_CHUNK_SIZE) break
            }
        }

        processedCount
    }

    suspend fun getAllTradeUps(): List<TradeUpResultResponse> = dbQuery {
        val results = TradeupsMaster
            .join(TradeupsCurrent, JoinType.LEFT,
                additionalConstraint = { TradeupsMaster.id eq TradeupsCurrent.tradeupId })
            .selectAll()
            .toList()
        mapToTradeUpResponsesBulk(results)
    }

    suspend fun getTradeUpById(id: Int): TradeUpResultResponse? = dbQuery {
        val results = TradeupsMaster
            .join(TradeupsCurrent, JoinType.LEFT,
                additionalConstraint = { TradeupsMaster.id eq TradeupsCurrent.tradeupId })
            .selectAll()
            .where { TradeupsMaster.id eq id }
            .toList()

        if (results.isEmpty()) null
        else mapToTradeUpResponsesBulk(results).firstOrNull()
    }

    suspend fun filterTradeUps(filter: TradeUpFilterRequest): PageResponse<TradeUpResultResponse> = dbQuery {
        var query = TradeupsMaster
            .join(TradeupsCurrent, JoinType.LEFT,
                additionalConstraint = { TradeupsMaster.id eq TradeupsCurrent.tradeupId })
            .selectAll()

        filter.minRoi?.let { minRoi ->
            query = query.andWhere { TradeupsCurrent.roi greaterEq minRoi }
        }

        filter.maxRoi?.let { maxRoi ->
            query = query.andWhere { TradeupsCurrent.roi lessEq maxRoi }
        }

        filter.minProfit?.let { minProfit ->
            query = query.andWhere { TradeupsCurrent.profit greaterEq minProfit }
        }

        filter.maxProfit?.let { maxProfit ->
            query = query.andWhere { TradeupsCurrent.profit lessEq maxProfit }
        }

        filter.stattrak?.let { stattrak ->
            query = query.andWhere { TradeupsMaster.stattrak eq stattrak }
        }

        filter.rarityId?.let { rarityId ->
            query = query.andWhere { TradeupsMaster.rarityId eq rarityId }
        }

        // Apply sorting
        query = when (filter.sortBy.lowercase()) {
            "profit" -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeupsCurrent.profit to SortOrder.ASC)
            } else {
                query.orderBy(TradeupsCurrent.profit to SortOrder.DESC)
            }
            "inputcost" -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeupsCurrent.inputCost to SortOrder.ASC)
            } else {
                query.orderBy(TradeupsCurrent.inputCost to SortOrder.DESC)
            }
            "updatedat" -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeupsCurrent.updatedAt to SortOrder.ASC)
            } else {
                query.orderBy(TradeupsCurrent.updatedAt to SortOrder.DESC)
            }
            else -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeupsCurrent.roi to SortOrder.ASC)
            } else {
                query.orderBy(TradeupsCurrent.roi to SortOrder.DESC)
            }
        }

        // Count total results for pagination
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

    suspend fun deleteAllTradeUps(): Int = dbQuery {
        val deletedSnapshots = TradeupSnapshots.deleteAll()
        val deletedCurrent = TradeupsCurrent.deleteAll()
        val deletedOutputs = TradeUpOutputs.deleteAll()
        val deletedInputs = TradeUpInputs.deleteAll()
        val deletedMaster = TradeupsMaster.deleteAll()
        deletedSnapshots + deletedCurrent + deletedOutputs + deletedInputs + deletedMaster
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
                    inputCost = rows.map { it[TradeupSnapshots.inputCost] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    outputCost = rows.map { it[TradeupSnapshots.outputCost] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
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

        // Fetch all inputs in one query
        val inputsByResultId = TradeUpInputs.selectAll()
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

        // Fetch all outputs in one query
        val outputsByResultId = TradeUpOutputs.selectAll()
            .where { TradeUpOutputs.tradeUpResultId inList resultIds }
            .map { row ->
                row[TradeUpOutputs.tradeUpResultId] to TradeUpOutputInfo(
                    skinId = row[TradeUpOutputs.skinId],
                    skinName = row[TradeUpOutputs.skinName],
                    probability = row[TradeUpOutputs.probability],
                    floatValue = row[TradeUpOutputs.floatValue],
                    price = row[TradeUpOutputs.price]
                )
            }
            .groupBy({ it.first }, { it.second })

        // Fetch all unique collections in one query
        val collectionIds = results.flatMap {
            listOf(it[TradeupsMaster.collectionAId], it[TradeupsMaster.collectionBId])
        }.distinct()

        val collectionsById = Collections.selectAll()
            .where { Collections.collectionId inList collectionIds }
            .associate {
                it[Collections.collectionId] to CollectionInfo(
                    it[Collections.collectionId],
                    it[Collections.name]
                )
            }

        // Fetch all unique rarities in one query
        val rarityIds = results.mapNotNull { it[TradeupsMaster.rarityId] }.distinct()
        val raritiesById = if (rarityIds.isNotEmpty()) {
            Rarities.selectAll()
                .where { Rarities.rarityId inList rarityIds }
                .associate {
                    it[Rarities.rarityId] to RarityInfo(
                        it[Rarities.rarityId],
                        it[Rarities.name],
                        it[Rarities.colorHex]
                    )
                }
        } else {
            emptyMap()
        }

        // Now map each result using the pre-fetched data
        return results.map { row ->
            val resultId = row[TradeupsMaster.id]
            val collectionAId = row[TradeupsMaster.collectionAId]
            val collectionBId = row[TradeupsMaster.collectionBId]
            val rarityId = row[TradeupsMaster.rarityId]

            TradeUpResultResponse(
                id = resultId,
                collectionA = collectionsById[collectionAId]
                    ?: CollectionInfo(collectionAId, collectionAId),
                collectionB = collectionsById[collectionBId]
                    ?: CollectionInfo(collectionBId, collectionBId),
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
}


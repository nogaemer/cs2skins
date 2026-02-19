package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.*
import database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.springframework.stereotype.Service
import tradeup.*
import java.math.BigDecimal

@Service
class TradeUpService(
    private val collectionRepository: CollectionRepository = CollectionRepository()
) {

    companion object {
        // Profitability thresholds for saving trade-ups
        private const val MIN_ROI_THRESHOLD = 1.1  // Minimum 10% return on investment
        private const val MAX_INPUT_COST = 10.0    // Maximum input cost in currency units
        private const val MIN_PROFIT_THRESHOLD = 0.10  // Minimum profit in currency units
        private const val MAX_OUTPUT_FLOAT = 0.4   // Maximum acceptable output float value
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    /**
     * Calculate all trade-ups and persist them to the database
     */
    suspend fun calculateAndSaveTradeUps(stattrak: Boolean = false): Int = withContext(Dispatchers.IO) {
        val optimizer = TradeUpOptimizer(collectionRepository)
        val collections = collectionRepository.findAll()
        var savedCount = 0

        for (i in collections.indices) {
            val collectionA = collections[i]
            val collectionWithSkinsA = optimizer.getCollectionWithSkins(collectionA, stattrak)

            for (j in i until collections.size) {
                val collectionB = collections[j]
                val collectionWithSkinsB = optimizer.getCollectionWithSkins(collectionB, stattrak)
                
                savedCount += calculateAndSaveForPair(
                    optimizer,
                    collectionWithSkinsA,
                    collectionWithSkinsB,
                    stattrak
                )
            }
        }

        savedCount
    }

    private suspend fun calculateAndSaveForPair(
        optimizer: TradeUpOptimizer,
        collectionA: models.CollectionWithSkins,
        collectionB: models.CollectionWithSkins,
        stattrak: Boolean
    ): Int {
        // Compute rarities and trade-ups OUTSIDE the transaction
        val rarities: List<String> = run {
            val order = listOf("Consumer Grade", "Industrial Grade", "Mil-Spec Grade", "Restricted", "Classified", "Covert")
            (collectionA.skins.keys intersect collectionB.skins.keys)
                .sortedWith(compareBy<String> { s -> val idx = order.indexOf(s); if (idx == -1) Int.MAX_VALUE else idx })
                .toList()
        }
        if (rarities.isEmpty()) return 0

        // Calculate all trade-ups first (CPU-intensive work outside transaction)
        val tradeUpsToSave = mutableListOf<Pair<TradeUp, Pair<String, String>>>() // (TradeUp, (rarityName, actualRarityId))

        for (i in rarities.indices) {
            if (i + 1 >= rarities.size) continue

            val rarityId = rarities[i]
            val skinsA = collectionA.skins[rarityId].orEmpty()
            val skinsB = collectionB.skins[rarityId].orEmpty()

            val skinsOutputA = collectionA.skins[rarities[i + 1]].orEmpty()
            val skinsOutputB = collectionB.skins[rarities[i + 1]].orEmpty()

            val outputSkins = skinsOutputA.union(skinsOutputB.toSet()).toMutableList()
            val outputFloats = optimizer.calculateOutputfloatsDTO(outputSkins)

            outputFloats.forEach { outputFloat ->
                val tradeUpOutput: TradeUpOutput = optimizer.calculateTradeUpOutputDTO(outputSkins, outputFloat)
                var bestTradeUpInputForOutputFloat: TradeUpInput? = null

                for (j in 1..9) {
                    skinsA.forEach { skinA ->
                        skinsB.forEach { skinB ->
                            val tradeUpInputComponentA =
                                TradeUpInputComponent(optimizer.skinDtoToSkin(skinA), j, collectionA.collectionId)
                            val tradeUpInputComponentB =
                                TradeUpInputComponent(optimizer.skinDtoToSkin(skinB), 10 - j, collectionB.collectionId)

                            val tradeUpInput: CostsFloatInput? =
                                TradeUpInput(tradeUpInputComponentA, tradeUpInputComponentB)
                                    .calculateBestFloats(outputFloat).values.minByOrNull { it.costs }

                            if (tradeUpInput != null &&
                                (tradeUpInput.costsWithDropChange < (bestTradeUpInputForOutputFloat?.costsFloatInput?.costsWithDropChange
                                    ?: Double.POSITIVE_INFINITY))
                            ) {
                                bestTradeUpInputForOutputFloat = TradeUpInput(
                                    tradeUpInputComponentA,
                                    tradeUpInputComponentB,
                                    tradeUpInput
                                )
                            }
                        }
                    }
                }

                if (bestTradeUpInputForOutputFloat != null) {
                    val tradeUp = TradeUp(
                        bestTradeUpInputForOutputFloat,
                        tradeUpOutput
                    )

                    // Check if profitable
//                    tradeUp.roiWithDropChange > MIN_ROI_THRESHOLD &&
//                            tradeUp.inputCostWithDropChange < MAX_INPUT_COST &&
//                            tradeUp.profitWithDropChange > MIN_PROFIT_THRESHOLD &&
//                            outputFloat < MAX_OUTPUT_FLOAT
                    if (true) {
                        tradeUpsToSave.add(tradeUp to (rarityId to rarityId))
                    }
                }
            }
        }

        // Now save all results in a SHORT transaction
        return dbQuery {
            var savedCount = 0
            tradeUpsToSave.forEach { (tradeUp, rarityInfo) ->
                val (rarityName, _) = rarityInfo
                // Resolve actual rarity ID from rarity name
                val actualRarityId = Rarities
                    .selectAll().where { Rarities.name eq rarityName }
                    .limit(1)
                    .singleOrNull()
                    ?.get(Rarities.rarityId)
                    ?: rarityName
                
                saveTradeUp(tradeUp, collectionA.collectionId, collectionB.collectionId, actualRarityId, stattrak)
                savedCount++
            }
            savedCount
        }
    }

    private fun saveTradeUp(
        tradeUp: TradeUp,
        collectionAId: String,
        collectionBId: String,
        rarityId: String,
        stattrak: Boolean
    ) {
        // Calculate average float from input components
        val avgFloat = ((tradeUp.input.costsFloatInput?.floatA ?: 0.0) * tradeUp.input.tradeUpInputComponentA.amount + 
                        (tradeUp.input.costsFloatInput?.floatB ?: 0.0) * tradeUp.input.tradeUpInputComponentB.amount) / 10.0
        
        // Extract values safely
        val roiValue: Double = tradeUp.roiWithDropChange.let { if (it.isFinite()) it else 0.0 }
        val profitValue: Double = tradeUp.profitWithDropChange.let { if (it.isFinite()) it else 0.0 }
        val inputCostValue: Double = tradeUp.inputCostWithDropChange.let { if (it.isFinite()) it else 0.0 }
        val outputCostValue: Double = tradeUp.expectedReturn.let { if (it.isFinite()) it else 0.0 }
        
        // Insert trade-up result
        val resultId = TradeUpResults.insert {
            it[TradeUpResults.collectionAId] = collectionAId
            it[TradeUpResults.collectionBId] = collectionBId
            it[TradeUpResults.rarityId] = rarityId
            it[TradeUpResults.stattrak] = stattrak
            it[TradeUpResults.outputFloat] = avgFloat
            it[TradeUpResults.roi] = roiValue
            it[TradeUpResults.profit] = profitValue
            it[TradeUpResults.inputCost] = inputCostValue
            it[TradeUpResults.outputCost] = outputCostValue
        }[TradeUpResults.id]

        // Insert input components
        TradeUpInputs.insert {
            it[TradeUpInputs.tradeUpResultId] = resultId
            it[TradeUpInputs.skinId] = tradeUp.input.tradeUpInputComponentA.skin.skinId
            it[TradeUpInputs.skinName] = tradeUp.input.tradeUpInputComponentA.skin.name
            it[TradeUpInputs.amount] = tradeUp.input.tradeUpInputComponentA.amount
            it[TradeUpInputs.floatValue] = tradeUp.input.costsFloatInput?.floatA ?: 0.0
            it[TradeUpInputs.pricePerUnit] = BigDecimal(tradeUp.input.tradeUpInputComponentA.skin.price.values.firstOrNull() ?: 0.0)
        }

        TradeUpInputs.insert {
            it[TradeUpInputs.tradeUpResultId] = resultId
            it[TradeUpInputs.skinId] = tradeUp.input.tradeUpInputComponentB.skin.skinId
            it[TradeUpInputs.skinName] = tradeUp.input.tradeUpInputComponentB.skin.name
            it[TradeUpInputs.amount] = tradeUp.input.tradeUpInputComponentB.amount
            it[TradeUpInputs.floatValue] = tradeUp.input.costsFloatInput?.floatB ?: 0.0
            it[TradeUpInputs.pricePerUnit] = BigDecimal(tradeUp.input.tradeUpInputComponentB.skin.price.values.firstOrNull() ?: 0.0)
        }

        // Insert output skins with probabilities
        // Group outputs by collection to calculate correct per-skin probabilities
        val outputsByCollection = tradeUp.output.skins.groupBy { it.collectionId }
        
        tradeUp.output.skins.forEach { outputSkin ->
            // Calculate drop probability based on collection
            val ballotsFromA = if (tradeUp.input.tradeUpInputComponentA.collectionId == outputSkin.collectionId) 
                tradeUp.input.tradeUpInputComponentA.amount else 0
            val ballotsFromB = if (tradeUp.input.tradeUpInputComponentB.collectionId == outputSkin.collectionId) 
                tradeUp.input.tradeUpInputComponentB.amount else 0
            val totalBallotsForCollection = ballotsFromA + ballotsFromB
            
            // Probability = (ballots for this collection / 10) / (number of skins in this collection)
            val skinsInCollection = outputsByCollection[outputSkin.collectionId]?.size ?: 1
            val probability = (totalBallotsForCollection.toDouble() / 10.0) / skinsInCollection.toDouble()
            
            TradeUpOutputs.insert {
                it[TradeUpOutputs.tradeUpResultId] = resultId
                it[TradeUpOutputs.skinId] = outputSkin.skinId
                it[TradeUpOutputs.skinName] = outputSkin.name
                it[TradeUpOutputs.probability] = probability
                it[TradeUpOutputs.floatValue] = outputSkin.float ?: 0.0
                it[TradeUpOutputs.price] = BigDecimal(outputSkin.price.values.firstOrNull() ?: 0.0)
            }
        }
    }

    suspend fun getAllTradeUps(): List<TradeUpResultResponse> = dbQuery {
        val results = TradeUpResults.selectAll().toList()
        mapToTradeUpResponsesBulk(results)
    }

    suspend fun getTradeUpById(id: Int): TradeUpResultResponse? = dbQuery {
        val results = TradeUpResults.selectAll()
            .where { TradeUpResults.id eq id }
            .toList()
        
        if (results.isEmpty()) null
        else mapToTradeUpResponsesBulk(results).firstOrNull()
    }

    suspend fun filterTradeUps(filter: TradeUpFilterRequest): PageResponse<TradeUpResultResponse> = dbQuery {
        var query = TradeUpResults.selectAll()

        filter.minRoi?.let { minRoi ->
            query = query.andWhere { TradeUpResults.roi greaterEq minRoi }
        }

        filter.maxRoi?.let { maxRoi ->
            query = query.andWhere { TradeUpResults.roi lessEq maxRoi }
        }

        filter.minProfit?.let { minProfit ->
            query = query.andWhere { TradeUpResults.profit greaterEq minProfit }
        }

        filter.maxProfit?.let { maxProfit ->
            query = query.andWhere { TradeUpResults.profit lessEq maxProfit }
        }

        filter.stattrak?.let { stattrak ->
            query = query.andWhere { TradeUpResults.stattrak eq stattrak }
        }

        filter.rarityId?.let { rarityId ->
            query = query.andWhere { TradeUpResults.rarityId eq rarityId }
        }

        // Apply sorting
        query = when (filter.sortBy.lowercase()) {
            "profit" -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeUpResults.profit to SortOrder.ASC)
            } else {
                query.orderBy(TradeUpResults.profit to SortOrder.DESC)
            }
            "inputcost" -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeUpResults.inputCost to SortOrder.ASC)
            } else {
                query.orderBy(TradeUpResults.inputCost to SortOrder.DESC)
            }
            "createdat" -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeUpResults.createdAt to SortOrder.ASC)
            } else {
                query.orderBy(TradeUpResults.createdAt to SortOrder.DESC)
            }
            else -> if (filter.sortDirection.lowercase() == "asc") {
                query.orderBy(TradeUpResults.roi to SortOrder.ASC)
            } else {
                query.orderBy(TradeUpResults.roi to SortOrder.DESC)
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
        TradeUpResults.deleteAll()
    }

    /**
     * Bulk mapping function that eliminates N+1 queries by fetching all related data in batches
     */
    private fun mapToTradeUpResponsesBulk(results: List<ResultRow>): List<TradeUpResultResponse> {
        if (results.isEmpty()) return emptyList()
        
        val resultIds = results.map { it[TradeUpResults.id] }
        
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
            listOf(it[TradeUpResults.collectionAId], it[TradeUpResults.collectionBId]) 
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
        val rarityIds = results.mapNotNull { it[TradeUpResults.rarityId] }.distinct()
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
            val resultId = row[TradeUpResults.id]
            val collectionAId = row[TradeUpResults.collectionAId]
            val collectionBId = row[TradeUpResults.collectionBId]
            val rarityId = row[TradeUpResults.rarityId]
            
            TradeUpResultResponse(
                id = resultId,
                collectionA = collectionsById[collectionAId] 
                    ?: CollectionInfo(collectionAId, collectionAId),
                collectionB = collectionsById[collectionBId] 
                    ?: CollectionInfo(collectionBId, collectionBId),
                rarity = rarityId?.let { raritiesById[it] },
                stattrak = row[TradeUpResults.stattrak],
                outputFloat = row[TradeUpResults.outputFloat],
                roi = row[TradeUpResults.roi],
                profit = row[TradeUpResults.profit],
                inputCost = row[TradeUpResults.inputCost],
                outputCost = row[TradeUpResults.outputCost],
                inputs = inputsByResultId[resultId] ?: emptyList(),
                outputs = outputsByResultId[resultId] ?: emptyList(),
                createdAt = row[TradeUpResults.createdAt]
            )
        }
    }

    private fun mapToTradeUpResponse(row: ResultRow): TradeUpResultResponse {
        val resultId = row[TradeUpResults.id]
        
        // Fetch inputs
        val inputs = TradeUpInputs.selectAll()
            .where { TradeUpInputs.tradeUpResultId eq resultId }
            .map {
                TradeUpInputInfo(
                    skinId = it[TradeUpInputs.skinId],
                    skinName = it[TradeUpInputs.skinName],
                    amount = it[TradeUpInputs.amount],
                    floatValue = it[TradeUpInputs.floatValue],
                    pricePerUnit = it[TradeUpInputs.pricePerUnit]
                )
            }

        // Fetch outputs
        val outputs = TradeUpOutputs.selectAll()
            .where { TradeUpOutputs.tradeUpResultId eq resultId }
            .map {
                TradeUpOutputInfo(
                    skinId = it[TradeUpOutputs.skinId],
                    skinName = it[TradeUpOutputs.skinName],
                    probability = it[TradeUpOutputs.probability],
                    floatValue = it[TradeUpOutputs.floatValue],
                    price = it[TradeUpOutputs.price]
                )
            }

        // Fetch collection and rarity info
        val collectionAId = row[TradeUpResults.collectionAId]
        val collectionBId = row[TradeUpResults.collectionBId]
        val rarityId = row[TradeUpResults.rarityId]

        val collectionA = Collections.selectAll()
            .where { Collections.collectionId eq collectionAId }
            .map { CollectionInfo(it[Collections.collectionId], it[Collections.name]) }
            .firstOrNull() ?: CollectionInfo(collectionAId, collectionAId)

        val collectionB = Collections.selectAll()
            .where { Collections.collectionId eq collectionBId }
            .map { CollectionInfo(it[Collections.collectionId], it[Collections.name]) }
            .firstOrNull() ?: CollectionInfo(collectionBId, collectionBId)

        val rarity = rarityId?.let {
            Rarities.selectAll()
                .where { Rarities.rarityId eq it }
                .map { RarityInfo(it[Rarities.rarityId], it[Rarities.name], it[Rarities.colorHex]) }
                .firstOrNull()
        }

        return TradeUpResultResponse(
            id = resultId,
            collectionA = collectionA,
            collectionB = collectionB,
            rarity = rarity,
            stattrak = row[TradeUpResults.stattrak],
            outputFloat = row[TradeUpResults.outputFloat],
            roi = row[TradeUpResults.roi],
            profit = row[TradeUpResults.profit],
            inputCost = row[TradeUpResults.inputCost],
            outputCost = row[TradeUpResults.outputCost],
            inputs = inputs,
            outputs = outputs,
            createdAt = row[TradeUpResults.createdAt]
        )
    }
}

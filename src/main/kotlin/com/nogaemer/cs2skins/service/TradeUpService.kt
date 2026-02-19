package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.*
import database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.springframework.stereotype.Service
import tradeup.*
import java.math.BigDecimal

@Service
class TradeUpService(
    private val collectionRepository: CollectionRepository = CollectionRepository()
) {

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
    ): Int = dbQuery {
        val rarities: List<String> = run {
            val order = listOf("Consumer Grade", "Industrial Grade", "Mil-Spec Grade", "Restricted", "Classified", "Covert")
            (collectionA.skins.keys intersect collectionB.skins.keys)
                .sortedWith(compareBy<String> { s -> val idx = order.indexOf(s); if (idx == -1) Int.MAX_VALUE else idx })
                .toList()
        }
        if (rarities.isEmpty()) return@dbQuery 0

        var savedCount = 0

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

                    // Save profitable trade-ups
                    if (tradeUp.roiWithDropChange > 1.1 && 
                        tradeUp.inputCostWithDropChange < 10 && 
                        tradeUp.profitWithDropChange > 0.10 && 
                        outputFloat < 0.4) {
                        
                        saveTradeUp(tradeUp, collectionA.collectionId, collectionB.collectionId, rarityId, stattrak)
                        savedCount++
                    }
                }
            }
        }

        savedCount
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
            it[TradeUpInputs.skinId] = tradeUp.input.tradeUpInputComponentA.skin.name // Using name as ID fallback
            it[TradeUpInputs.amount] = tradeUp.input.tradeUpInputComponentA.amount
            it[TradeUpInputs.floatValue] = tradeUp.input.costsFloatInput?.floatA ?: 0.0
            it[TradeUpInputs.pricePerUnit] = BigDecimal(tradeUp.input.tradeUpInputComponentA.skin.price.values.firstOrNull() ?: 0.0)
        }

        TradeUpInputs.insert {
            it[TradeUpInputs.tradeUpResultId] = resultId
            it[TradeUpInputs.skinId] = tradeUp.input.tradeUpInputComponentB.skin.name // Using name as ID fallback
            it[TradeUpInputs.amount] = tradeUp.input.tradeUpInputComponentB.amount
            it[TradeUpInputs.floatValue] = tradeUp.input.costsFloatInput?.floatB ?: 0.0
            it[TradeUpInputs.pricePerUnit] = BigDecimal(tradeUp.input.tradeUpInputComponentB.skin.price.values.firstOrNull() ?: 0.0)
        }

        // Insert output skins with probabilities
        tradeUp.output.skins.forEach { outputSkin ->
            // Calculate drop probability based on collection
            val ballotsFromA = if (tradeUp.input.tradeUpInputComponentA.collectionId == outputSkin.collectionId) 
                tradeUp.input.tradeUpInputComponentA.amount else 0
            val ballotsFromB = if (tradeUp.input.tradeUpInputComponentB.collectionId == outputSkin.collectionId) 
                tradeUp.input.tradeUpInputComponentB.amount else 0
            val probability = (ballotsFromA + ballotsFromB) / 10.0
            
            TradeUpOutputs.insert {
                it[TradeUpOutputs.tradeUpResultId] = resultId
                it[TradeUpOutputs.skinId] = outputSkin.name // Using name as ID fallback
                it[TradeUpOutputs.probability] = probability
                it[TradeUpOutputs.floatValue] = outputSkin.float ?: 0.0
                it[TradeUpOutputs.price] = BigDecimal(outputSkin.price.values.firstOrNull() ?: 0.0)
            }
        }
    }

    suspend fun getAllTradeUps(): List<TradeUpResultResponse> = dbQuery {
        TradeUpResults.selectAll()
            .map { mapToTradeUpResponse(it) }
    }

    suspend fun getTradeUpById(id: Int): TradeUpResultResponse? = dbQuery {
        TradeUpResults.selectAll()
            .where { TradeUpResults.id eq id }
            .map { mapToTradeUpResponse(it) }
            .singleOrNull()
    }

    suspend fun filterTradeUps(filter: TradeUpFilterRequest): List<TradeUpResultResponse> = dbQuery {
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

        query.map { mapToTradeUpResponse(it) }
    }

    suspend fun deleteAllTradeUps(): Int = dbQuery {
        TradeUpResults.deleteAll()
    }

    private fun mapToTradeUpResponse(row: ResultRow): TradeUpResultResponse {
        val resultId = row[TradeUpResults.id]
        
        // Fetch inputs
        val inputs = TradeUpInputs.selectAll()
            .where { TradeUpInputs.tradeUpResultId eq resultId }
            .map {
                TradeUpInputInfo(
                    skinId = it[TradeUpInputs.skinId],
                    skinName = it[TradeUpInputs.skinId], // Using ID as name for now
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
                    skinName = it[TradeUpOutputs.skinId], // Using ID as name for now
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

package tradeup

import database.clickhouse.TradeupOutcomeSnapshotWriter
import database.clickhouse.TradeupSnapshotWriter
import database.postgres.*
import database.postgres.Collection
import models.CSWear
import models.CollectionWithItems
import models.Skin
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class TradeUpOptimizer(
    private val catalogRepository: CatalogRepository,
    private val recipeRepository: TradeUpRecipeRepository,
    private val recipeOutcomeRepository: TradeUpRecipeOutcomeRepository,
    private val runRepository: CalculatorRunRepository,
    private val snapshotWriter: TradeupSnapshotWriter,
    private val outcomeWriter: TradeupOutcomeSnapshotWriter,
    private val algorithmVersion: String = "1.0.0"
) {

    private val wearBucketIdByCswear: Map<CSWear, Short> by lazy {
        val byCode = catalogRepository.findAllWearBuckets().associateBy { it.code }
        CSWear.entries.associateWith { wear ->
            byCode[wear.id]?.id ?: error("No wear bucket found for code ${wear.id}")
        }
    }

    private val rarityNameById: Map<Short, String> by lazy {
        catalogRepository.findAllRaritiesOrdered().associate { it.id to it.name }
    }

    private val rarityIdByName: Map<String, Short> by lazy {
        catalogRepository.findAllRaritiesOrdered().associate { it.name to it.id }
    }

    private val gameId: Short by lazy { catalogRepository.findGameId("cs2") }

    private val skinCache = ConcurrentHashMap<Long, Skin>()

    private data class OrderedRecipeInput(
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val skin1Count: Int,
        val skin2Count: Int,
        val skin1Float: Double,
        val skin2Float: Double,
        val skin1WearBucketId: Short,
        val skin2WearBucketId: Short
    )

    private data class RecipeGroupKey(
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val skin1Count: Int,
        val skin2Count: Int,
        val wearBucketId: Short
    )

    private data class PendingCandidate(
        val recipeInput: TradeUpRecipeRepository.RecipeInput,
        val orderedInput: OrderedRecipeInput,
        val tradeUp: TradeUp,
        val tradeUpOutput: TradeUpOutput,
        val outputFloat: Double
    )

    fun optimizeAll() {
        val runId = runRepository.startRun(
            intervalLabel = "manual",
            calculatorVersion = algorithmVersion
        )

        val totalPersisted = java.util.concurrent.atomic.AtomicLong(0)

        try {
            val collections = catalogRepository.findAllCollections()
            val pairsPerPass = collections.size.toLong() * (collections.size + 1) / 2
            val totalPairs = pairsPerPass * 2
            val pairsDone = java.util.concurrent.atomic.AtomicLong(0)

            fun printProgress() {
                val done = pairsDone.get()
                val percent = if (totalPairs == 0L) 100.0 else done * 100.0 / totalPairs
                val barWidth = 30
                val filled = (percent / 100.0 * barWidth).toInt().coerceIn(0, barWidth)
                val bar = "=".repeat(filled) + " ".repeat(barWidth - filled)
                print(
                    "\r[$bar] %6.2f%% (%d/%d pairs) | %d recipes persisted   ".format(
                        percent, done, totalPairs, totalPersisted.get()
                    )
                )
                System.out.flush()
            }

            val jobs = mutableListOf<Pair<Int, Int>>()
            for (i in collections.indices) for (j in i until collections.size) jobs.add(i to j)

            val threadCount = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)

            try {
                listOf(false, true).forEach { allowStattrak ->
                    val futures = jobs.map { (i, j) ->
                        executor.submit {
                            val collectionA = collections[i]
                            val collectionB = collections[j]
                            val itemsA = getCollectionWithItems(collectionA, allowStattrak)
                            val itemsB = getCollectionWithItems(collectionB, allowStattrak)
                            val persisted = optimize(itemsA, itemsB, runId, allowStattrak)
                            totalPersisted.addAndGet(persisted)
                            pairsDone.incrementAndGet()
                            printProgress()
                        }
                    }
                    futures.forEach { it.get() }
                }
            } finally {
                executor.shutdown()
            }

            println()
            runRepository.finishRun(runId, "SUCCESS", totalPersisted.get())
        } catch (e: Exception) {
            println()
            runRepository.finishRun(runId, "FAILED", totalPersisted.get(), e.message)
            throw e
        }
    }

    fun optimize(
        collectionA: CollectionWithItems,
        collectionB: CollectionWithItems,
        runId: Long,
        allowStattrak: Boolean
    ): Long {
        val rarities: List<String> = run {
            val order = listOf(
                "Consumer Grade", "Industrial Grade", "Mil-Spec Grade",
                "Restricted", "Classified", "Covert"
            )
            (collectionA.itemsByRarity.keys intersect collectionB.itemsByRarity.keys)
                .sortedWith(compareBy { s -> order.indexOf(s).let { if (it == -1) Int.MAX_VALUE else it } })
                .toList()
        }

        if (rarities.isEmpty()) return 0

        var persisted = 0L
        val snapshotBatch = mutableListOf<TradeupSnapshotWriter.TradeupSnapshotRow>()
        val outcomeBatch = mutableListOf<TradeupOutcomeSnapshotWriter.OutcomeRow>()
        val recipeOutcomeBatch = mutableListOf<TradeUpRecipeOutcomeRepository.OutcomeInput>()
        val bestCandidateByGroup = mutableMapOf<RecipeGroupKey, PendingCandidate>()
        val snapshotAt = Instant.now()

        for (i in rarities.indices) {
            if (i + 1 >= rarities.size) continue

            val rarityName = rarities[i]
            val itemsA = collectionA.itemsByRarity[rarityName].orEmpty()
            val itemsB = collectionB.itemsByRarity[rarityName].orEmpty()

            val itemsOutputA = collectionA.itemsByRarity[rarities[i + 1]].orEmpty()
            val itemsOutputB = collectionB.itemsByRarity[rarities[i + 1]].orEmpty()

            val outputItems = itemsOutputA.union(itemsOutputB.toSet()).toMutableList()
            if (outputItems.isEmpty() || itemsA.isEmpty() || itemsB.isEmpty()) continue

            val inputRarityId = rarityIdByName[rarityName] ?: continue
            val outputRarityId = rarityIdByName[rarities[i + 1]] ?: continue

            val outputFloats = calculateOutputFloats(outputItems)

            outputFloats.forEach { outputFloat ->
                val tradeUpOutput = calculateTradeUpOutput(outputItems, outputFloat)

                for (j in 1..9) {
                    itemsA.forEach { itemA ->
                        itemsB.forEach { itemB ->
                            val skinA = itemToSkin(itemA)
                            val skinB = itemToSkin(itemB)

                            val tradeUpInputComponentA =
                                TradeUpInputComponent(skinA, j, collectionA.collectionId.toString())
                            val tradeUpInputComponentB =
                                TradeUpInputComponent(skinB, 10 - j, collectionB.collectionId.toString())

                            val costsFloatInput = TradeUpInput(tradeUpInputComponentA, tradeUpInputComponentB)
                                .calculateBestFloats(outputFloat)
                                .values
                                .minByOrNull { it.costsWithDropChange }
                                ?: return@forEach

                            val tradeUpInput = TradeUpInput(
                                tradeUpInputComponentA, tradeUpInputComponentB, costsFloatInput
                            )
                            val tradeUp = TradeUp(tradeUpInput, tradeUpOutput)

                            if (tradeUp.roiWithDropChange.isFinite() &&
                                tradeUp.inputCostWithDropChange.isFinite() &&
                                tradeUp.profitWithDropChange.isFinite()
                            ) {
                                val skin1ItemId = skinA.itemId
                                val skin2ItemId = skinB.itemId
                                val wearBucketId = wearBucketIdByCswear[CSWear.floatToCSWear(outputFloat)]

                                if (skin1ItemId != null && skin2ItemId != null && wearBucketId != null) {
                                    // Recipe repository enforces skin_1_item_id <= skin_2_item_id;
                                    // mirror that ordering here so the ClickHouse snapshot row
                                    // agrees with the Postgres recipe row it references.
                                    val skin1WearBucketIdRaw =
                                        wearBucketIdByCswear[CSWear.floatToCSWear(costsFloatInput.floatA)]
                                            ?: return@forEach
                                    val skin2WearBucketIdRaw =
                                        wearBucketIdByCswear[CSWear.floatToCSWear(costsFloatInput.floatB)]
                                            ?: return@forEach

                                    val orderedInput = if (skin1ItemId <= skin2ItemId) {
                                        OrderedRecipeInput(
                                            skin1ItemId,
                                            skin2ItemId,
                                            tradeUpInputComponentA.amount,
                                            tradeUpInputComponentB.amount,
                                            costsFloatInput.floatA,
                                            costsFloatInput.floatB,
                                            skin1WearBucketIdRaw,
                                            skin2WearBucketIdRaw          // <-- NEW, same order as floats
                                        )
                                    } else {
                                        OrderedRecipeInput(
                                            skin2ItemId,
                                            skin1ItemId,
                                            tradeUpInputComponentB.amount,
                                            tradeUpInputComponentA.amount,
                                            costsFloatInput.floatB,
                                            costsFloatInput.floatA,
                                            skin2WearBucketIdRaw,
                                            skin1WearBucketIdRaw          // <-- NEW, swapped with the floats
                                        )
                                    }

                                    val recipeInput = TradeUpRecipeRepository.RecipeInput(
                                        gameId = gameId,
                                        inputRarityId = inputRarityId,
                                        outputRarityId = outputRarityId,
                                        skin1ItemId = orderedInput.skin1ItemId,
                                        skin2ItemId = orderedInput.skin2ItemId,
                                        skin1Count = orderedInput.skin1Count,
                                        skin2Count = orderedInput.skin2Count,
                                        wearBucketId = wearBucketId,
                                        allowStattrak = allowStattrak
                                    )

                                    val groupKey = RecipeGroupKey(
                                        orderedInput.skin1ItemId, orderedInput.skin2ItemId,
                                        orderedInput.skin1Count, orderedInput.skin2Count,
                                        wearBucketId
                                    )

                                    val existing = bestCandidateByGroup[groupKey]
                                    if (existing == null || tradeUp.profitWithDropChange > existing.tradeUp.profitWithDropChange) {
                                        bestCandidateByGroup[groupKey] = PendingCandidate(
                                            recipeInput, orderedInput, tradeUp, tradeUpOutput, outputFloat
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val pendingCandidates = bestCandidateByGroup.values.toList()
        if (pendingCandidates.isEmpty()) return 0

        val recipeIdsByHash = recipeRepository.upsertRecipesBatch(
            pendingCandidates.map { it.recipeInput }
        )

        pendingCandidates.forEach { candidate ->
            val recipeId = recipeIdsByHash[candidate.recipeInput.canonicalHashHex] ?: return@forEach
            val orderedInput = candidate.orderedInput
            val tradeUp = candidate.tradeUp
            val tradeUpOutput = candidate.tradeUpOutput
            val outputFloat = candidate.outputFloat

            snapshotBatch.add(
                TradeupSnapshotWriter.TradeupSnapshotRow(
                    snapshotAt = snapshotAt,
                    runId = runId,
                    tradeupRecipeId = recipeId,
                    skin1ItemId = orderedInput.skin1ItemId,
                    skin2ItemId = orderedInput.skin2ItemId,
                    skin1Count = orderedInput.skin1Count,
                    skin2Count = orderedInput.skin2Count,
                    wearBucketId = candidate.recipeInput.wearBucketId.toInt(),
                    skin1WearBucketId = orderedInput.skin1WearBucketId.toInt(),
                    skin2WearBucketId = orderedInput.skin2WearBucketId.toInt(),
                    skin1Float = orderedInput.skin1Float.toFloat(),
                    skin2Float = orderedInput.skin2Float.toFloat(),
                    avgInputFloat = outputFloat.toFloat(),
                    avgRawInputFloat = outputFloat.toFloat(),
                    inputCost = tradeUp.inputCost,
                    inputCostWithDropChange = tradeUp.inputCostWithDropChange,
                    expectedValue = tradeUp.expectedReturn,
                    profitAbs = tradeUp.profit,
                    profitWithDropChange = tradeUp.profitWithDropChange,
                    roi = tradeUp.roi.toFloat(),
                    roiWithDropChange = tradeUp.roiWithDropChange.toFloat(),
                    profitChance = tradeUp.profitChance.toFloat(),
                    profitPercentage = tradeUp.profitPercentage.toFloat(),
                    outcomeCount = tradeUpOutput.skins.size,
                    algorithmVersion = algorithmVersion
                )
            )

            tradeUpOutput.skins.forEachIndexed { index, outcomeSkin ->
                val outcomeItemId = outcomeSkin.itemId ?: return@forEachIndexed
                val outFloat = outcomeSkin.float ?: return@forEachIndexed
                val outWear = CSWear.floatToCSWear(outFloat)
                val outWearBucketId = wearBucketIdByCswear[outWear] ?: return@forEachIndexed
                val price = outcomeSkin.price[outWear] ?: 0.0
                val probability = tradeUp.outcomeProbabilities.getOrElse(index) { 0.0 }

                outcomeBatch.add(
                    TradeupOutcomeSnapshotWriter.OutcomeRow(
                        snapshotAt = snapshotAt,
                        runId = runId,
                        tradeupRecipeId = recipeId,
                        outcomeItemId = outcomeItemId,
                        outcomeIndex = index,
                        outputFloat = outFloat.toFloat(),
                        outputWearBucketId = outWearBucketId.toInt(),
                        outcomeProbability = probability.toFloat(),
                        outcomePrice = price,
                        expectedContribution = price * probability,
                        algorithmVersion = algorithmVersion
                    )
                )

                recipeOutcomeBatch.add(
                    TradeUpRecipeOutcomeRepository.OutcomeInput(
                        tradeUpRecipeId = recipeId,
                        outcomeItemId = outcomeItemId,
                        theoreticalProbability = probability,
                        sourceCollectionId = outcomeSkin.collectionId.toLongOrNull()
                    )
                )
            }

            persisted++
        }

        recipeOutcomeRepository.upsertOutcomesBatch(recipeOutcomeBatch)
        snapshotWriter.insertBatch(snapshotBatch)
        outcomeWriter.insertBatch(outcomeBatch)

        return persisted
    }

    private fun calculateTradeUpOutput(outputItems: MutableList<Item>, avgFloat: Double): TradeUpOutput {
        val skins = outputItems.map { item ->
            itemToSkin(item).copy(
                float = convertInputFloatToOutputFloat(avgFloat, item.maxFloat, item.minFloat)
            )
        }.toMutableList()

        return TradeUpOutput(
            skins = skins,
            costs = skins.sumOf { skin -> skin.price[CSWear.floatToCSWear(skin.float!!)] ?: 0.0 }
        )
    }

    private fun calculateOutputFloats(inputs: List<Item>): List<Double> {
        return inputs.flatMap { input ->
            CSWear.entries.map { wear ->
                convertOutputFloatToInputFloat(wear.max - 0.0000001, input.maxFloat, input.minFloat)
            }
        }.distinct()
    }

    private fun convertOutputFloatToInputFloat(outputFloat: Double, maxFloat: Double, minFloat: Double): Double {
        return (outputFloat.coerceAtMost(maxFloat) - minFloat) / (maxFloat - minFloat)
    }

    private fun convertInputFloatToOutputFloat(inputFloat: Double, maxFloat: Double, minFloat: Double): Double {
        return inputFloat * (maxFloat - minFloat) + minFloat
    }

    private fun itemToSkin(item: Item): Skin {
        skinCache[item.id]?.let { return it }

        val priceMap = CSWear.entries.mapNotNull { wear ->
            val wearBucketId = wearBucketIdByCswear[wear] ?: return@mapNotNull null
            val price = catalogRepository.findCurrentPrice(item.id, wearBucketId) ?: return@mapNotNull null
            wear to price.averagePrice.toDouble()
        }.toMap().toMutableMap()

        val skin = Skin(
            name = item.name,
            collectionId = (item.collectionId ?: 0L).toString(),
            price = priceMap,
            minFloatCap = item.minFloat,
            maxFloatCap = item.maxFloat,
            itemId = item.id
        )

        skinCache[item.id] = skin
        return skin
    }

    private fun getCollectionWithItems(collection: Collection, stattrak: Boolean): CollectionWithItems {
        val items = catalogRepository.findItemsByCollection(collection.id, stattrak)
        val itemsByRarityName = mutableMapOf<String, MutableList<Item>>()

        items.forEach { item ->
            val rarityId = item.rarityId ?: return@forEach
            val rarityName = rarityNameById[rarityId] ?: return@forEach
            itemsByRarityName.getOrPut(rarityName) { mutableListOf() }.add(item)
        }

        return CollectionWithItems(
            collectionId = collection.id,
            name = collection.name,
            imageUrl = collection.imageUrl,
            itemsByRarity = itemsByRarityName
        )
    }
}
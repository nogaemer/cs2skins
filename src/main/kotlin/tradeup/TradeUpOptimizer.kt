package tradeup

import database.Collection
import database.CollectionRepository
import database.SkinDTO
import database.SkinRepository
import models.CSWear
import models.CollectionWithSkins
import models.Skin
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TradeUpOptimizer(
    val collectionRepository: CollectionRepository
) {

    private val logger = LoggerFactory.getLogger(TradeUpOptimizer::class.java)

    suspend fun optimizeAll() {
        val collections = collectionRepository.findAll()

        //none-stattrak
        for (i in collections.indices) {
            val collectionA = collections[i]
            val collectionWithSkinsA = getCollectionWithSkins(collectionA, false)

            for (j in i until collections.size) {
                val collectionB = collections[j]
                val collectionWithSkinsB = getCollectionWithSkins(collectionB, false)
                optimize(collectionWithSkinsA, collectionWithSkinsB)
            }
        }

        logger.info("${"#".repeat(25)} Stattrak ${"#".repeat(25)}")

        //stattrak
        for (i in collections.indices) {
            val collectionA = collections[i]
            val collectionWithSkinsA = getCollectionWithSkins(collectionA, true)

            for (j in i until collections.size) {
                val collectionB = collections[j]
                val collectionWithSkinsB = getCollectionWithSkins(collectionB, true)
                optimize(collectionWithSkinsA, collectionWithSkinsB)
            }
        }
    }

    fun optimize(collectionA: CollectionWithSkins, collectionB: CollectionWithSkins) {
        val rarities: List<String> = run {
            val order = listOf("Consumer Grade", "Industrial Grade", "Mil-Spec Grade", "Restricted", "Classified", "Covert")
            (collectionA.skins.keys intersect collectionB.skins.keys)
                .sortedWith(compareBy<String> { s -> val idx = order.indexOf(s); if (idx == -1) Int.MAX_VALUE else idx })
                .toList()
        }
        if (rarities.isEmpty()) return

        val tradeUps = mutableListOf<MutableMap<CSWear, CostsFloatInput>>()

        for (i in rarities.indices) {
            if (i + 1 >= rarities.size) continue

            val rarityId = rarities[i]
            val skinsA = collectionA.skins[rarityId].orEmpty()
            val skinsB = collectionB.skins[rarityId].orEmpty()

            val skinsOutputA = collectionA.skins[rarities[i + 1]].orEmpty()
            val skinsOutputB = collectionB.skins[rarities[i + 1]].orEmpty()

            val outputSkins = skinsOutputA.union(skinsOutputB.toSet()).toMutableList()
            val outputFloats = calculateOutputfloatsDTO(outputSkins)

            outputFloats.forEach { outputFloat ->
                val tradeUpOutput: TradeUpOutput = calculateTradeUpOutputDTO(outputSkins, outputFloat)
                var bestTradeUpInputForOutputFloat: TradeUpInput? = null

//                println(skinsA[0].collectionId + " " + skinsB[0].collectionId)
                for (j in 1..9) {

                    skinsA.forEach { skinA ->
                        skinsB.forEach { skinB ->
                            val tradeUpInputComponentA =
                                TradeUpInputComponent(skinDtoToSkin(skinA), j, collectionA.collectionId)
                            val tradeUpInputComponentB =
                                TradeUpInputComponent(skinDtoToSkin(skinB), 10 - j, collectionB.collectionId)

                            val tradeUpInput: CostsFloatInput? =
                                TradeUpInput(tradeUpInputComponentA, tradeUpInputComponentB)
                                    .calculateBestFloats(outputFloat).values.minByOrNull { it.costsWithDropChange }

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

                    if (tradeUp.roiWithDropChange > 1.1 && tradeUp.inputCostWithDropChange < 10 && tradeUp.profitWithDropChange > 0.10 && outputFloat < 0.4) {
                        val aStr = "${tradeUp.input.tradeUpInputComponentA.amount}x ${tradeUp.input.tradeUpInputComponentA.skin.name} - ${tradeUp.input.costsFloatInput!!.floatA}"
                        val bStr = "${tradeUp.input.tradeUpInputComponentB.amount}x ${tradeUp.input.tradeUpInputComponentB.skin.name} - ${tradeUp.input.costsFloatInput.floatB}"
                        if (logger.isDebugEnabled) {
                            logger.debug(
                                String.format(
                                    "%-60s %-60s float %-22s | roi %6.2f | profit %8.2f | rarity %s",
                                    aStr,
                                    bStr,
                                    outputFloat.toString(),
                                    tradeUp.roiWithDropChange,
                                    tradeUp.profitWithDropChange,
                                    rarityId
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun calculateTradeUpOutputDTO(outputSkins: MutableList<SkinDTO>, avgFloat: Double): TradeUpOutput {
        val skins = outputSkins.map { skin ->
            skinDtoToSkin(skin).copy(
                float = convertInputFloatToOutputFloat(
                    avgFloat,
                    skin.maxFloat,
                    skin.minFloat
                )
            )
        }.toMutableList()

        return TradeUpOutput(
            skins = skins,
            costs = skins.sumOf { skin -> skin.price[CSWear.floatToCSWear(skin.float!!)] ?: 0.0 }
        )
    }

    fun calculateTradeUpOutput(outputSkins: MutableList<Skin>, avgFloat: Double): TradeUpOutput {
        val skins = outputSkins.map { skin ->
            skin.copy(
                float = convertInputFloatToOutputFloat(
                    avgFloat,
                    skin.maxFloatCap,
                    skin.minFloatCap
                )
            )
        }.toMutableList()

        return TradeUpOutput(
            skins = skins,
            costs = skins.sumOf { skin -> skin.price[CSWear.floatToCSWear(skin.float!!)] ?: 0.0 }
        )
    }

    fun calculateOutputfloatsDTO(inputs: List<SkinDTO>): List<Double> {
        return inputs.flatMap { input ->
            CSWear.entries.mapNotNull { wear ->
                if (wear.max > input.maxFloat
                    || wear.min < input.minFloat) return@mapNotNull null

                val converted = convertOutputFloatToInputFloat(
                    wear.max - 1e-13,
                    input.maxFloat,
                    input.minFloat
                )
                return@mapNotNull (converted * 1e12).toLong() / 1e12
            }
        }.distinct()
    }

    fun calculateOutputfloats(inputs: List<Skin>): List<Double> {
        return inputs.flatMap { input ->
            CSWear.entries.map { wear ->
                val converted = convertOutputFloatToInputFloat(
                    wear.max - 0.0000001,
                    input.maxFloatCap,
                    input.minFloatCap
                )
                converted
            }
        }.distinct()
    }

    fun convertOutputFloatToInputFloat(outputFloat: Double, maxFloat: Double, minFloat: Double): Double {
        return (outputFloat.coerceAtMost(maxFloat) - minFloat) / (maxFloat - minFloat)
    }

    fun convertInputFloatToOutputFloat(inputFloat: Double, maxFloat: Double, minFloat: Double): Double {
        return inputFloat * (maxFloat - minFloat) + minFloat
    }

    fun skinDtoToSkin(skinDTO: SkinDTO): Skin {
        val priceMap: MutableMap<CSWear, Double> =
            skinDTO.price.mapValues { (_, skinPrice) -> skinPrice.price.toDouble() }.toMutableMap()

        return Skin(
            skinId = skinDTO.skinId,
            name = skinDTO.name,
            collectionId = skinDTO.collectionId!!,
            price = priceMap,
            minFloatCap = skinDTO.minFloat,
            maxFloatCap = skinDTO.maxFloat
        )
    }

    suspend fun getCollectionWithSkins(collection: Collection, stattrak: Boolean = false): CollectionWithSkins {
        val skinsOfCollectionA = SkinRepository().findByCollectionWithPrice(collection.collectionId, stattrak)
        val skinsByRarity: MutableMap<String, MutableList<SkinDTO>> = mutableMapOf()

        skinsOfCollectionA.forEach { skin ->
            skinsByRarity.getOrPut(skin.rarity.name) { mutableListOf() }.add(skin)
        }

        return CollectionWithSkins(
            collectionId = collection.collectionId,
            name = collection.name,
            image = collection.image,
            skins = skinsByRarity
        )
    }
}
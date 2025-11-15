package tradeup

import database.Collection
import database.CollectionRepository
import database.SkinDTO
import database.SkinRepository
import kotlinx.coroutines.runBlocking
import models.CSWear
import models.CollectionWithSkins
import models.Skin

class TradeUpOptimizer(
    val collectionRepository: CollectionRepository = CollectionRepository()
) {

    fun optimizeAll() {
        val collections = runBlocking { collectionRepository.findAll() }

        collections.forEach { collectionA ->
            val collectionWithSkinsA = getCollectionWithSkins(collectionA)

            collections.forEach { collectionB ->
                optimize(collectionWithSkinsA, getCollectionWithSkins(collectionB))
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

                    if (tradeUp.roiWithDropChange > 1.2 && tradeUp.inputCostWithDropChange < 10) {
                        val aStr = "${tradeUp.input.tradeUpInputComponentA.amount}x ${tradeUp.input.tradeUpInputComponentA.skin.name} - ${tradeUp.input.costsFloatInput!!.floatA}"
                        val bStr = "${tradeUp.input.tradeUpInputComponentB.amount}x ${tradeUp.input.tradeUpInputComponentB.skin.name} - ${tradeUp.input.costsFloatInput.floatB}"
                        println(String.format("%-60s %-60s float %-22s | roi %6.2f | profit %8.2f | rarity %s", aStr, bStr, outputFloat.toString(), tradeUp.roiWithDropChange, tradeUp.profitWithDropChange, rarityId))
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
            CSWear.entries.map { wear ->
                val converted = convertOutputFloatToInputFloat(
                    wear.max - 0.0000001,
                    input.maxFloat,
                    input.minFloat
                )
                converted
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
            name = skinDTO.name,
            collectionId = skinDTO.collectionId!!,
            price = priceMap,
            minFloatCap = skinDTO.minFloat,
            maxFloatCap = skinDTO.maxFloat
        )
    }

    fun getCollectionWithSkins(collection: Collection): CollectionWithSkins {
        val skinsOfCollectionA = runBlocking { SkinRepository().findByCollectionWithPrice(collection.collectionId) }
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
package tradeup

import models.CSWear
import kotlin.math.sqrt

class TradeUpInput(
    val tradeUpInputComponentA: TradeUpInputComponent,
    val tradeUpInputComponentB: TradeUpInputComponent,
    val costsFloatInput: CostsFloatInput? = null,
) {

    fun calculateBestFloats(avgFloat: Double): MutableMap<CSWear, CostsFloatInput> {
        val dropProbability = DropProbability(tradeUpInputComponentA, tradeUpInputComponentB)

        val floatAtLowestPriceMap = mutableMapOf<CSWear, CostsFloatInput>()

        val dropProbabilityListMapB: MutableMap<CSWear, List<ProbabilityLinear>> =
            CSWear.entries.associateWith { wearSkinB ->
                dropProbability.getFloatProbability(wearSkinB, tradeUpInputComponentB.skin)
                    .map { pointPair -> dropProbability.probabilityLinear(pointPair) }
                    .map { it.adjust(avgFloat, tradeUpInputComponentA, tradeUpInputComponentB) }
                    .toList()
            }.toMutableMap()

        CSWear.entries.forEach { wearSkinA ->
            val dropProbabilityListA = dropProbability.getFloatProbability(wearSkinA, tradeUpInputComponentA.skin)
                .map { pointPair -> dropProbability.probabilityLinear(pointPair) }.toList()

            val costsFloatInputList = mutableListOf<CostsFloatInput>()

            CSWear.entries.forEach { wearSkinB ->
                val dropProbabilityListB = dropProbabilityListMapB[wearSkinB] ?: emptyList()
                val priceA = tradeUpInputComponentA.skin.price[wearSkinA]?.takeIf { it > 0.0 } ?: return@forEach
                val priceB = tradeUpInputComponentB.skin.price[wearSkinB]?.takeIf { it > 0.0 } ?: return@forEach

                dropProbabilityListA.forEach { dropProbabilityA ->
                    val slopeA = dropProbabilityA.m
                    val interceptA = dropProbabilityA.b

                    if (slopeA == 0.0) return@forEach

                    val constA = tradeUpInputComponentA.amount * priceA * slopeA

                    dropProbabilityListB.forEach { dropProbabilityB ->
                        if (dropProbabilityA.minXRange > dropProbabilityB.maxXRange) return@forEach
                        if (dropProbabilityA.maxXRange < dropProbabilityB.minXRange) return@forEach

                        val slopeB = dropProbabilityB.m
                        val interceptB = dropProbabilityB.b

                        val constB = tradeUpInputComponentB.amount * priceB * (-slopeB)

                        if (constA <= 0.0 || constB <= 0.0) return@forEach

                        val slopeRatio = slopeB / slopeA
                        val interceptDiff = interceptB - slopeRatio * interceptA
                        val sqrtRatio = sqrt(constB / constA)

                        val denominator = sqrtRatio - slopeRatio
                        if (denominator == 0.0) return@forEach

                        val floatAtLowestPrice = (interceptDiff / denominator - interceptA) / slopeA

                        val minXRange = maxOf(dropProbabilityA.minXRange, dropProbabilityB.minXRange)
                        val maxXRange = minOf(dropProbabilityA.maxXRange, dropProbabilityB.maxXRange)

                        if (floatAtLowestPrice < minXRange) return@forEach
                        if (floatAtLowestPrice > maxXRange) return@forEach

                        val probabilityA = slopeA * floatAtLowestPrice + interceptA
                        val probabilityB = slopeB * floatAtLowestPrice + interceptB
                        if (probabilityA <= 0.0 || probabilityB <= 0.0) return@forEach

                        val priceIncA = 0.13 * priceA * (1.0 / probabilityA - 1.0) + priceA
                        val priceIncB = 0.13 * priceB * (1.0 / probabilityB - 1.0) + priceB

                        val finalPrice = tradeUpInputComponentA.amount * priceA + tradeUpInputComponentB.amount * priceB
                        val finalPriceWithDropChange =
                            tradeUpInputComponentA.amount * priceIncA + tradeUpInputComponentB.amount * priceIncB

                        val costFloatInput = CostsFloatInput(
                            finalPriceWithDropChange,
                            finalPrice,
                            floatA = floatAtLowestPrice,
                            floatB = (
                                    (avgFloat * 10.0
                                            - tradeUpInputComponentA.amount * ((floatAtLowestPrice - tradeUpInputComponentA.skin.minFloatCap)
                                            / tradeUpInputComponentA.skin.floatCapDifference)) / tradeUpInputComponentB.amount)
                                    * tradeUpInputComponentB.skin.floatCapDifference
                                    + tradeUpInputComponentB.skin.minFloatCap

                        )

                        if ((tradeUpInputComponentA.skin.price[CSWear.floatToCSWear(costFloatInput.floatA)]
                                ?: 0.0) <= 0.0
                        ) return@forEach

                        if ((tradeUpInputComponentB.skin.price[CSWear.floatToCSWear(costFloatInput.floatB)]
                                ?: 0.0) <= 0.0
                        ) return@forEach

                        costsFloatInputList.add(costFloatInput)
                    }
                }
            }

            floatAtLowestPriceMap[wearSkinA] = costsFloatInputList.minByOrNull { it.costsWithDropChange } ?: return@forEach
        }
        return floatAtLowestPriceMap
    }
}

data class CostsFloatInput(
    val costsWithDropChange: Double,
    val costs: Double,
    val floatA: Double,
    val floatB: Double,
)
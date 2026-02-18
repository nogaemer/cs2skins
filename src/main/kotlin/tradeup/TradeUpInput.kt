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
                val priceA = tradeUpInputComponentA.skin.price[wearSkinA]?.takeIf { it != 0.0 } ?: return@forEach
                val priceB = tradeUpInputComponentB.skin.price[wearSkinB]?.takeIf { it != 0.0 } ?: return@forEach

                dropProbabilityListA.forEach { dropProbabilityA ->

                    // Linear forms: P_A(x) = m_A * x + b_A and P_B(x) = m_B * x + b_B.
                    val slopeA = dropProbabilityA.m
                    val interceptA = dropProbabilityA.b

                    // Guard: later math divides by m_A; if m_A == 0 there is no valid solution here.
                    if (slopeA == 0.0) return@forEach

                    // Constants contributing to the price-change objective.
                    val constA = tradeUpInputComponentA.amount * priceA * slopeA

                    dropProbabilityListB.forEach { dropProbabilityB ->
                        // Skip if the linear segments have no overlap on the x-axis.
                        if (dropProbabilityA.minXRange > dropProbabilityB.maxXRange) return@forEach
                        if (dropProbabilityA.maxXRange < dropProbabilityB.minXRange) return@forEach

                        // Linear forms: P_A(x) = m_A * x + b_A and P_B(x) = m_B * x + b_B.
                        val slopeB = dropProbabilityB.m
                        val interceptB = dropProbabilityB.b

                        // Constants contributing to the price-change objective.
                        val constB = tradeUpInputComponentB.amount * priceB * (-slopeB)

                        // Both constants must be positive for the square-root and the model to be valid.
                        if (constA <= 0.0 || constB <= 0.0) return@forEach

                        // Precompute helper ratios used by the closed-form optimum.
                        val slopeRatio = slopeB / slopeA
                        val interceptDiff = interceptB - slopeRatio * interceptA
                        val sqrtRatio = sqrt(constB / constA)

                        // Denominator of the solution; avoid division by zero.
                        val denominator = sqrtRatio - slopeRatio
                        if (denominator == 0.0) return@forEach

                        // Closed-form optimal float within the overlapping domain.
                        val floatAtLowestPrice = (interceptDiff / denominator - interceptA) / slopeA

                        // Compute the intersection of the valid x-range for both probabilities.
                        val minXRange = maxOf(dropProbabilityA.minXRange, dropProbabilityB.minXRange)
                        val maxXRange = minOf(dropProbabilityA.maxXRange, dropProbabilityB.maxXRange)

                        // Reject if the solution falls outside the overlap.
                        if (floatAtLowestPrice < minXRange) return@forEach
                        if (floatAtLowestPrice > maxXRange) return@forEach

                        // Calculate probabilities at x = floatAtLowestPrice
                        val probabilityA = slopeA * floatAtLowestPrice + interceptA
                        val probabilityB = slopeB * floatAtLowestPrice + interceptB
                        if (probabilityA <= 0.0 || probabilityB <= 0.0) return@forEach

                        // Price increase formula:
                        // priceInc_{?}(x) = 0.13 * price_{?} * (1 / Probability_{?}(x) - 1) + price_{?}

                        val priceIncA = 0.13 * priceA * (1.0 / probabilityA - 1.0) + priceA
                        val priceIncB = 0.13 * priceB * (1.0 / probabilityB - 1.0) + priceB

                        // finalPrice(x) = amountA * priceIncA + amountB * priceIncB
                        val finalPrice = tradeUpInputComponentA.amount * priceA + tradeUpInputComponentB.amount * priceB
                        val finalPriceWithDropChange = tradeUpInputComponentA.amount * priceIncA + tradeUpInputComponentB.amount * priceIncB

                        costsFloatInputList.add(
                            CostsFloatInput(
                                finalPriceWithDropChange,
                                finalPrice,
                                floatA = floatAtLowestPrice,
                                floatB = (
                                        (avgFloat * 10.0
                                                - tradeUpInputComponentA.amount * ((floatAtLowestPrice - tradeUpInputComponentA.skin.minFloatCap) / tradeUpInputComponentA.skin.floatCapDifference))
                                                * tradeUpInputComponentB.skin.floatCapDifference
                                                + tradeUpInputComponentB.skin.minFloatCap
                                        ) / tradeUpInputComponentB.amount

                            )
                        )
                    }
                }
            }

            floatAtLowestPriceMap[wearSkinA] = costsFloatInputList.minByOrNull { it.costs } ?: return@forEach
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
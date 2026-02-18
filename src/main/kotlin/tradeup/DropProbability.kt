package tradeup

import models.CSWear
import models.Skin

class DropProbability(
    tradeUpInputComponentA: TradeUpInputComponent,
    tradeUpInputComponentB: TradeUpInputComponent
) {

    companion object {
        fun calculateMMultiplier(
            tradeUpInputComponentA: TradeUpInputComponent,
            tradeUpInputComponentB: TradeUpInputComponent,
        ): Double {
            require(tradeUpInputComponentA.amount != 0) { "skinAmountA must not be zero" }
            return -((tradeUpInputComponentA.amount.toDouble() * tradeUpInputComponentB.skin.floatCapDifference)
                    / (tradeUpInputComponentB.amount.toDouble() * tradeUpInputComponentA.skin.floatCapDifference))
        }

        fun calculateBSummand(
            avgFloat: Double,
            tradeUpInputComponentB: TradeUpInputComponent,
        ): Double {
            return avgFloat * 10.0 * tradeUpInputComponentB.skin.floatCapDifference / tradeUpInputComponentB.amount.toDouble()
        }
    }

    data class FloatProbabilityPoint(val float: Double, val probability: Double)
    data class FloatProbabilityPointPair(
        val lower: FloatProbabilityPoint,
        val upper: FloatProbabilityPoint,
        val floatDifference: Double
    ) {
        override fun toString(): String = "\nFloatProbabilityPointPair(\n\tlower=$lower,\n\tupper=$upper\n)"
    }

    fun calculateMMultiplier(
        skinAmountA: Int,
        skinAmountB: Int
    ): Double {
        require(skinAmountA != 0) { "skinAmountA must not be zero" }
        return -skinAmountA.toDouble() / skinAmountB.toDouble()
    }

    fun calculateBSummand(
        avgFloat: Double,
        skinAmountB: Int,
    ): Double {
        return avgFloat * 10.0 / skinAmountB.toDouble()
    }

    fun probabilityLinear(
        pointPair: FloatProbabilityPointPair,
        floatDifference: Double = 0.0,
    ): ProbabilityLinear {
        require(pointPair.upper.float > pointPair.lower.float) { "xHigh must be > xLow" }
        val pMin = pointPair.lower.probability
        val pMax = pointPair.upper.probability
        val xLow = pointPair.lower.float
        val xHigh = pointPair.upper.float

        val m = (pMax - pMin) / (xHigh - xLow)
        val b = pMin + m * -xLow

        return ProbabilityLinear(
            m,
            b,
            pointPair
        )
    }

    fun probabilityLinear(
        pointPairs: MutableList<FloatProbabilityPointPair>
    ): MutableList<ProbabilityLinear> {
        return pointPairs.map {
            probabilityLinear(
                it,
            )
        }.toMutableList()
    }

    fun getFloatProbability(
        floatRange: CSWear,
        skin: Skin,
    ): MutableList<FloatProbabilityPointPair> {
        var combinedProbability = 0.0;
        var floatProbabilityPoints: MutableList<FloatProbabilityPoint> = mutableListOf()
        val floatProbabilityPointsPairs: MutableList<FloatProbabilityPointPair> = mutableListOf()

        for (float in CSWear.entries) {
            val minFloat = float.generationMin * skin.floatCapDifference + skin.minFloatCap
            val maxFloat = float.max * skin.floatCapDifference + skin.minFloatCap
            if (maxFloat >= floatRange.min && floatRange.max >= minFloat) {
                val clampedMin = minFloat.coerceAtLeast(floatRange.min)
                val clampedMax = maxFloat.coerceAtMost(floatRange.max)
                val difference = clampedMax - clampedMin
                if (difference == 0.0) continue

                val probability = (difference * (float.probability / (maxFloat - minFloat)))
                combinedProbability += probability

                floatProbabilityPointsPairs.add(
                    FloatProbabilityPointPair(
                        FloatProbabilityPoint(clampedMin, 0.0),
                        FloatProbabilityPoint(clampedMax, probability),
                        skin.floatCapDifference
                    )
                )
            }
        }

        var totalProbability = 0.0
        return floatProbabilityPointsPairs.map { (lower, upper, floatDifference) ->
            val lowerProbabilityPoint = FloatProbabilityPoint(lower.float, totalProbability)

            val upperNormalizedProb = if (upper.probability != 0.0) (upper.probability / combinedProbability) else 0.0
            val upperProbabilityPoint = FloatProbabilityPoint(upper.float, upperNormalizedProb + totalProbability)

            totalProbability += upperNormalizedProb
            FloatProbabilityPointPair(lowerProbabilityPoint, upperProbabilityPoint, floatDifference)
        }.toMutableList()
    }

    val SkinBFloatMin = 0.0
    val SkinBFloatMax = 0.6
    val SkinBFloatDifference = SkinBFloatMax - SkinBFloatMin
    val SkinBFloatblockMWMinPercentage = 0.0      // 0%
    val SkinBFloatblockMWMaxPercentage = 0.4585   // 45.85%

    // A: MW over [0.08*ΔA, 0.15*ΔA]
//    val probA = probabilityLinear(
//        pMin = 0.0,
//        pMax = getFloatProbability(float, skinA)[CSFloat.MINIMAL_WEAR] ?: 0.0,
//        xLow = 0.07000,
//        xHigh = float.max * skinA.floatCapDifference
//    )

    // B: MW over [0.07, 0.15*ΔB] as specified
//    val probB = probabilityLinear(
//        pMin = SkinBFloatblockMWMinPercentage,
//        pMax = SkinBFloatblockMWMaxPercentage,
//        xLow = SkinBFloatMin + 0.07,
//        xHigh = SkinBFloatMin + 0.15 * SkinBFloatDifference
//    )
}
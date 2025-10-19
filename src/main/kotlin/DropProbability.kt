import kotlin.math.abs

class DropProbability(
    tradeUpComponentA: TradeUpComponent,
    tradeUpComponentB: TradeUpComponent
) {
    data class ProbabilityLinear(
        val m: Double,
        val b: Double,
        val floatProbabilityPointPair: FloatProbabilityPointPair,
        val wear: CSWear? = null,
        val maxXRange: Double? = null
    ) {
        fun at(x: Double): Double = m * x + b

        override fun toString(): String = "f(x) = $m * x ${if (b < 0) "-" else "+"}${abs(b)} \t\t maxXRange=$maxXRange"
    }

    data class FloatProbabilityPoint(val float: Double, val probability: Double)
    data class FloatProbabilityPointPair(val lower: FloatProbabilityPoint, val upper: FloatProbabilityPoint) {
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
        float: CSWear? = null,
        mMultiplier: Double? = null,
        bSummand: Double? = null,
        skinAmountA: Int? = null,
        skinAmountB: Int? = null,
        avgFloat: Double? = null,
    ): ProbabilityLinear {
        require(pointPair.upper.float > pointPair.lower.float) { "xHigh must be > xLow" }
        val pMin = pointPair.lower.probability
        val pMax = pointPair.upper.probability
        val xLow = pointPair.lower.float
        val xHigh = pointPair.upper.float

        val m = (pMax - pMin) / (xHigh - xLow)
        val b = m * -xLow

        val mAdjusted = m * (mMultiplier ?: 1.0)
        val bAdjusted = if (bSummand != null) (bSummand - xLow) * m else b

        val maxXRange = if (0 > mAdjusted && skinAmountA != null && skinAmountB != null && avgFloat != null) {
            (-pointPair.lower.float * skinAmountB + avgFloat * 10.0) / skinAmountA
        } else null

        return ProbabilityLinear(
            mAdjusted,
            bAdjusted,
            pointPair,
            float,
            maxXRange
        )
    }

    fun probabilityLinear(
        pointPairs: MutableList<FloatProbabilityPointPair>,
        float: CSWear? = null,
        mMultiplier: Double? = null,
        bSummand: Double? = null,
        skinAmountA: Int? = null,
        skinAmountB: Int? = null,
        avgFloat: Double? = null,
    ): MutableList<ProbabilityLinear> {
        return pointPairs.map {
            probabilityLinear(
                it,
                float,
                mMultiplier,
                bSummand,
                skinAmountA,
                skinAmountB,
                avgFloat
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

        CSWear.entries.forEach { float ->
            val minFloat = float.generationMin * skin.floatCapDifference
            val maxFloat = float.max * skin.floatCapDifference
            if (maxFloat >= floatRange.min && floatRange.max >= minFloat) {
                val clampedMin = minFloat.coerceAtLeast(floatRange.min);
                val clampedMax = maxFloat.coerceAtMost(floatRange.max);
                val difference = clampedMax - clampedMin

                val probability = (difference * (float.probability / (maxFloat - minFloat)))
                combinedProbability += probability

                if (floatProbabilityPoints.isEmpty()) floatProbabilityPoints.add(FloatProbabilityPoint(clampedMin, 0.0))
                val floatProbabilityPoint = FloatProbabilityPoint(clampedMax, probability)
                floatProbabilityPoints.add(floatProbabilityPoint)
            }
        }

        floatProbabilityPoints = floatProbabilityPoints.map { (float, probability) ->
            val normalizedProb = if (probability != 0.0) (probability / combinedProbability) else 0.0
            FloatProbabilityPoint(float, normalizedProb)
        }.toMutableList()

        var totalProbability = 0.0
        for (index in 0 until floatProbabilityPoints.lastIndex) {
            val lower = floatProbabilityPoints[index]
            val upper = floatProbabilityPoints[index + 1]

            totalProbability += lower.probability
            floatProbabilityPointsPairs.add(
                FloatProbabilityPointPair(
                    FloatProbabilityPoint(lower.float, totalProbability),
                    FloatProbabilityPoint(upper.float, totalProbability + upper.probability)
                )
            )
        }
        return floatProbabilityPointsPairs
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
package tradeup

import kotlin.math.abs

class ProbabilityLinear(
    val m: Double,
    val b: Double,
    val floatProbabilityPointPair: DropProbability.FloatProbabilityPointPair,
    val minXRange: Double = floatProbabilityPointPair.lower.float,
    val maxXRange: Double = floatProbabilityPointPair.upper.float,
) {
    fun at(x: Double): Double = m * x + b

    fun adjust(
        avgFloat: Double,
        tradeUpInputComponentA: TradeUpInputComponent,
        tradeUpInputComponentB: TradeUpInputComponent,
    ): ProbabilityLinear {
        val xLow = floatProbabilityPointPair.lower.float
        val xHigh = floatProbabilityPointPair.upper.float
        val mMultiplier = DropProbability.calculateMMultiplier(tradeUpInputComponentA, tradeUpInputComponentB)
        val bSummand = DropProbability.calculateBSummand(avgFloat, tradeUpInputComponentB)

        val mAdjusted = m * (mMultiplier ?: 1.0)
        val bAdjusted = if (bSummand != null) (bSummand - xLow) * m else b

        val maxXRange = if (0 > mAdjusted) {
            (tradeUpInputComponentA.skin.floatCapDifference / tradeUpInputComponentA.amount
                    * (avgFloat * 10 - xLow * tradeUpInputComponentB.amount / tradeUpInputComponentB.skin.floatCapDifference))
        } else null

        val minXRange = if (0 > mAdjusted) {
            (tradeUpInputComponentA.skin.floatCapDifference / tradeUpInputComponentA.amount
                    * (avgFloat * 10 - xHigh * tradeUpInputComponentB.amount / tradeUpInputComponentB.skin.floatCapDifference))
        } else null

        return ProbabilityLinear(
            mAdjusted,
            bAdjusted,
            floatProbabilityPointPair,
            minXRange ?: this.minXRange,
            maxXRange ?: this.maxXRange
        )
    }

    override fun toString(): String = "f(x) = $m * x ${if (b < 0) "-" else "+"}${abs(b)} \t($minXRange <= x <= $maxXRange)"
}
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

    fun invertXRanges(): ProbabilityLinear = ProbabilityLinear(
        m = m,
        b = b,
        floatProbabilityPointPair = floatProbabilityPointPair,
        minXRange = maxXRange,
        maxXRange = minXRange
    )


    fun adjust(
        avgFloat: Double,
        tradeUpInputComponentA: TradeUpInputComponent,
        tradeUpInputComponentB: TradeUpInputComponent,
    ): ProbabilityLinear {
        val xLow = floatProbabilityPointPair.lower.float
        val xHigh = floatProbabilityPointPair.upper.float
        val mMultiplier = DropProbability.calculateMMultiplier(tradeUpInputComponentA, tradeUpInputComponentB)
        val bSummand = DropProbability.calculateBSummand(avgFloat, tradeUpInputComponentA, tradeUpInputComponentB)

        val mAdjusted = m * mMultiplier
//      val bAdjusted = if (bSummand != null) (bSummand - xLow) * m else b
        val bAdjusted = b + m * bSummand

        val skinAFloatDifference = tradeUpInputComponentA.skin.floatCapDifference
        val skinAAmount = tradeUpInputComponentA.amount
        val skinAFloatMin = tradeUpInputComponentA.skin.minFloatCap
        val skinBFloatDifference = tradeUpInputComponentB.skin.floatCapDifference
        val skinBAmount = tradeUpInputComponentB.amount
        val skinBFloatMin = tradeUpInputComponentB.skin.minFloatCap

        val maxXRange = if (0 > mAdjusted) {
            ((avgFloat * 10 - skinBAmount * (xLow - skinBFloatMin) / skinBFloatDifference) / skinAAmount) * skinAFloatDifference + skinAFloatMin
        } else null

        val minXRange = if (0 > mAdjusted) {
            ((avgFloat * 10 - skinBAmount * (xHigh - skinBFloatMin) / skinBFloatDifference) / skinAAmount) * skinAFloatDifference + skinAFloatMin
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
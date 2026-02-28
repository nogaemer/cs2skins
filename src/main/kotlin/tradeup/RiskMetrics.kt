package tradeup

import models.CSWear

/**
 * Risk metrics derived from a trade-up's output-value distribution.
 *
 * @param probProfit fraction of outcomes whose value strictly exceeds [costBasis] (0.0–1.0)
 * @param variance   population variance of the distribution
 * @param p05        5th-percentile value (linear interpolation on the sorted distribution)
 * @param p50        50th-percentile (median) value
 * @param p95        95th-percentile value
 */
data class RiskMetrics(
    val probProfit: Double,
    val variance: Double,
    val p05: Double,
    val p50: Double,
    val p95: Double,
)

/**
 * Computes [RiskMetrics] for the given [distribution] of outcome values.
 *
 * @param distribution list of monetary outcome values (need not be sorted)
 * @param costBasis    the input cost used as the break-even threshold for [RiskMetrics.probProfit]
 */
fun computeRiskMetrics(distribution: List<Double>, costBasis: Double): RiskMetrics {
    if (distribution.isEmpty()) {
        return RiskMetrics(probProfit = 0.0, variance = 0.0, p05 = 0.0, p50 = 0.0, p95 = 0.0)
    }

    val n = distribution.size
    val probProfit = distribution.count { it > costBasis }.toDouble() / n

    val mean = distribution.sum() / n
    val variance = distribution.sumOf { (it - mean) * (it - mean) } / n

    val sorted = distribution.sorted()
    return RiskMetrics(
        probProfit = probProfit,
        variance = variance,
        p05 = percentile(sorted, 5.0),
        p50 = percentile(sorted, 50.0),
        p95 = percentile(sorted, 95.0),
    )
}

/**
 * Computes [RiskMetrics] for a [tradeUp] using its ballot-weighted probability model.
 *
 * Each output skin's probability is proportional to the number of ballots contributed
 * by the input components whose collection matches that skin, which aligns the risk
 * metrics with [TradeUp.expectedReturn] and [TradeUp.profit].
 *
 * @param tradeUp   the trade-up whose output distribution to analyse
 * @param costBasis the input cost used as the break-even threshold for [RiskMetrics.probProfit]
 */
fun computeRiskMetrics(tradeUp: TradeUp, costBasis: Double): RiskMetrics {
    val ballotsPerSkin = tradeUp.output.skins.mapNotNull { skin ->
        val f = skin.float ?: return@mapNotNull null
        val wear = CSWear.floatToCSWear(f)
        val value = skin.price[wear] ?: return@mapNotNull null
        val ballots = ballotsForSkin(tradeUp, skin.collectionId)
        if (ballots == 0) null else Pair(value, ballots)
    }
    if (ballotsPerSkin.isEmpty()) {
        return RiskMetrics(probProfit = 0.0, variance = 0.0, p05 = 0.0, p50 = 0.0, p95 = 0.0)
    }
    // Expand each outcome value by its ballot count to form the weighted discrete distribution.
    // Ballot counts are bounded integers (sum = 10 for CS2 trade-ups), so the list stays small.
    val weightedDistribution = ballotsPerSkin.flatMap { (value, ballots) -> List(ballots) { value } }
    return computeRiskMetrics(weightedDistribution, costBasis)
}

/** Returns the total ballot count for a skin belonging to [collectionId] given the trade-up's input components. */
private fun ballotsForSkin(tradeUp: TradeUp, collectionId: String): Int {
    val fromA = if (tradeUp.input.tradeUpInputComponentA.collectionId == collectionId)
        tradeUp.input.tradeUpInputComponentA.amount else 0
    val fromB = if (tradeUp.input.tradeUpInputComponentB.collectionId == collectionId)
        tradeUp.input.tradeUpInputComponentB.amount else 0
    return fromA + fromB
}


internal fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return 0.0
    if (sorted.size == 1) return sorted[0]
    val clampedP = p.coerceIn(0.0, 100.0)
    val idx = (clampedP / 100.0) * (sorted.size - 1)
    val lo = idx.toInt()
    val hi = (lo + 1).coerceAtMost(sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
}

package tradeup

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
 * Returns the [p]-th percentile of a **pre-sorted** list using linear interpolation.
 * [p] is in the range [0, 100].
 */
internal fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return 0.0
    if (sorted.size == 1) return sorted[0]
    val idx = (p / 100.0) * (sorted.size - 1)
    val lo = idx.toInt()
    val hi = (lo + 1).coerceAtMost(sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
}

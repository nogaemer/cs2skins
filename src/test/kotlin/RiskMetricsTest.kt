import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tradeup.RiskMetrics
import tradeup.computeRiskMetrics
import tradeup.percentile

class RiskMetricsTest {

    // Known distribution: [1.0, 2.0, 3.0, 4.0, 5.0]
    // mean   = 3.0
    // variance = ((1-3)^2 + (2-3)^2 + (3-3)^2 + (4-3)^2 + (5-3)^2) / 5
    //          = (4 + 1 + 0 + 1 + 4) / 5 = 2.0
    // p05: idx = 0.05 * 4 = 0.2 → sorted[0] + (sorted[1]-sorted[0])*0.2 = 1.0 + 0.2 = 1.2
    // p50: idx = 0.50 * 4 = 2.0 → sorted[2] = 3.0
    // p95: idx = 0.95 * 4 = 3.8 → sorted[3] + (sorted[4]-sorted[3])*0.8 = 4.0 + 0.8 = 4.8
    private val knownDistribution = listOf(1.0, 2.0, 3.0, 4.0, 5.0)

    @Test
    fun `computeRiskMetrics returns correct variance for known distribution`() {
        val metrics = computeRiskMetrics(knownDistribution, costBasis = 2.5)
        assertEquals(2.0, metrics.variance, 1e-9)
    }

    @Test
    fun `computeRiskMetrics returns correct p50 for known distribution`() {
        val metrics = computeRiskMetrics(knownDistribution, costBasis = 2.5)
        assertEquals(3.0, metrics.p50, 1e-9)
    }

    @Test
    fun `computeRiskMetrics returns correct p05 for known distribution`() {
        val metrics = computeRiskMetrics(knownDistribution, costBasis = 2.5)
        assertEquals(1.2, metrics.p05, 1e-9)
    }

    @Test
    fun `computeRiskMetrics returns correct p95 for known distribution`() {
        val metrics = computeRiskMetrics(knownDistribution, costBasis = 2.5)
        assertEquals(4.8, metrics.p95, 1e-9)
    }

    @Test
    fun `computeRiskMetrics returns correct probProfit for cost basis below median`() {
        // values > 2.5 are [3, 4, 5] → prob = 3/5 = 0.6
        val metrics = computeRiskMetrics(knownDistribution, costBasis = 2.5)
        assertEquals(0.6, metrics.probProfit, 1e-9)
    }

    @Test
    fun `computeRiskMetrics returns probProfit zero when cost basis exceeds all outcomes`() {
        val metrics = computeRiskMetrics(knownDistribution, costBasis = 10.0)
        assertEquals(0.0, metrics.probProfit, 1e-9)
    }

    @Test
    fun `computeRiskMetrics returns probProfit one when cost basis is below all outcomes`() {
        val metrics = computeRiskMetrics(knownDistribution, costBasis = 0.0)
        assertEquals(1.0, metrics.probProfit, 1e-9)
    }

    @Test
    fun `computeRiskMetrics on empty distribution returns all zeros`() {
        val metrics = computeRiskMetrics(emptyList(), costBasis = 5.0)
        assertEquals(RiskMetrics(probProfit = 0.0, variance = 0.0, p05 = 0.0, p50 = 0.0, p95 = 0.0), metrics)
    }

    @Test
    fun `computeRiskMetrics on single-element distribution`() {
        val metrics = computeRiskMetrics(listOf(7.0), costBasis = 5.0)
        assertEquals(1.0, metrics.probProfit, 1e-9)  // 7 > 5
        assertEquals(0.0, metrics.variance, 1e-9)
        assertEquals(7.0, metrics.p05, 1e-9)
        assertEquals(7.0, metrics.p50, 1e-9)
        assertEquals(7.0, metrics.p95, 1e-9)
    }

    @Test
    fun `percentile returns min for p=0 and max for p=100`() {
        val sorted = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, percentile(sorted, 0.0), 1e-9)
        assertEquals(5.0, percentile(sorted, 100.0), 1e-9)
    }

    @Test
    fun `computeRiskMetrics handles unsorted input correctly`() {
        // Same values as knownDistribution but in reverse; results must be identical
        val reversed = listOf(5.0, 4.0, 3.0, 2.0, 1.0)
        val metrics = computeRiskMetrics(reversed, costBasis = 2.5)
        assertEquals(0.6, metrics.probProfit, 1e-9)
        assertEquals(2.0, metrics.variance, 1e-9)
        assertEquals(1.2, metrics.p05, 1e-9)
        assertEquals(3.0, metrics.p50, 1e-9)
        assertEquals(4.8, metrics.p95, 1e-9)
    }
}

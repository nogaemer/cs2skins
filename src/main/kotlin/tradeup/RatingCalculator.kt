package tradeup

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Per-(item, wear) market microstructure snapshot, sourced from
 * item_current_prices' openskin columns. Pulled separately from [models.Skin]
 * rather than folded into it, to keep this additive and avoid touching every
 * existing Skin call site.
 *
 * All percentage-scale fields (spreadPct, slippagePct, priceImpact*Pct,
 * volatility7d) are in the SAME UNITS openskin returns them in: percentage
 * points, e.g. 10.48 means 10.48%, not 0.1048. Confirmed against a real
 * openskin response (Tec-9 | Jambiya, Well-Worn): spread.percent=12.96,
 * slippage.percent=27.03, volatility["7d"]=10.48 -- all clearly percentage
 * points, not fractions. Do not rescale these when reading from Postgres.
 *
 * priceImpact5Pct / priceImpact10Pct are null when openskin's price_impact
 * object doesn't have a "5"/"10" key at all -- that's what "insufficient
 * order-book depth to fill that quantity" looks like in the JSON (the key is
 * omitted entirely, not present-with-null). Treated by [RatingCalculator] as
 * a hard "can't actually buy this" signal, not as missing data to smooth over.
 */
data class SkinMarketMetrics(
    val liquidityScore: Double?,
    val spreadPct: Double?,
    val slippagePct: Double?,
    val priceImpact5Pct: Double?,
    val priceImpact10Pct: Double?,
    val volatility7d: Double?
)

data class RatingResult(
    val rating: Double,
    val depthGate: Double,
    val volatilityCombined7d: Double,
    val roiScore: Double,
    val profitChanceScore: Double,
    val execCostScore: Double,
    val volScore: Double,
    val liquidityScore: Double
)

/**
 * Weighted-geometric-mean trade-up rating combining ROI, profit chance,
 * execution cost (spread + slippage + price impact), price volatility
 * (compounded across the two mandatory 7-day Steam trade-lock windows), and
 * order-book liquidity -- with a hard depth-sufficiency gate multiplied in
 * afterward so a recipe that can't actually be filled at the required
 * quantity sinks to the bottom regardless of how good its other components
 * look on paper.
 *
 * Conservative placeholders are used wherever openskin data is missing,
 * rather than defaulting to 0 (unfairly kills the recipe) or 1 (falsely
 * inflates it) -- see the *_PLACEHOLDER constants below.
 */
object RatingCalculator {

    // --- weights (sum to 1.0) ---
    const val WEIGHT_ROI = 0.25
    const val WEIGHT_PROFIT_CHANCE = 0.15
    const val WEIGHT_EXEC_COST = 0.15
    const val WEIGHT_VOLATILITY = 0.25
    const val WEIGHT_LIQUIDITY = 0.20

    // --- normalization thresholds ---
    private const val ROI_SIGMOID_K = 4.0 // ROI=0 -> 0.5, ROI=0.20 -> ~0.73

    // Execution cost (spread% + slippage% + price-impact%) at which score = 0.5.
    // Real observed total for a moderately-illiquid item (Tec-9 Jambiya WW):
    // 12.96 + 27.03 + 16.39 = 56.38 -- so 30.0 keeps typical illiquid items in
    // the 0.3-0.4 range rather than crushing everything toward 0.
    private const val EXEC_COST_THRESHOLD_PCT = 30.0

    // Volatility (openskin's volatility.7d, ALREADY in percentage points, e.g.
    // 10.48 means 10.48%) at which score = 0.5. Calibrated against real data:
    // a typical 7d volatility of ~10-15 should land near the middle of the
    // scale, not near zero -- the previous 0.05 threshold assumed a fractional
    // scale and would have scored every real item near 0 regardless of actual
    // risk. Confirmed via the same Tec-9 Jambiya example (volatility.7d=10.48).
    private const val VOLATILITY_THRESHOLD = 12.0

    // --- conservative placeholders when openskin data is missing ---
    private const val EXEC_COST_PLACEHOLDER_PCT = 40.0 // -> execCostScore ~= 0.43 at threshold 30
    private const val VOLATILITY_PLACEHOLDER = 15.0     // percentage points -> volScore ~= 0.44
    private const val LIQUIDITY_PLACEHOLDER = 30.0      // out of 100

    private const val DEPTH_GATE_PENALTY = 0.1
    private const val DEPTH_GATE_OK = 1.0

    /**
     * @param tradeUp the computed trade-up (source of ROI/profit-chance)
     * @param inputA market metrics for input skin A, at the float it's used at
     * @param inputB market metrics for input skin B, at the float it's used at
     * @param requiredQtyA how many units of skin A this recipe needs (1..9)
     * @param requiredQtyB how many units of skin B this recipe needs (1..9)
     * @param outputMetrics market metrics for each possible outcome skin,
     *   indexed the same as [tradeup.TradeUp.outcomeProbabilities]
     */
    fun calculate(
        tradeUp: TradeUp,
        inputA: SkinMarketMetrics,
        inputB: SkinMarketMetrics,
        requiredQtyA: Int,
        requiredQtyB: Int,
        outputMetrics: List<SkinMarketMetrics>
    ): RatingResult {
        val roiScore = sigmoid(ROI_SIGMOID_K * tradeUp.roiWithDropChange)
        val profitChanceScore = tradeUp.profitChance.coerceIn(0.0, 1.0)

        val execCostA = execCostPct(inputA)
        val execCostB = execCostPct(inputB)
        val execCostOutput = probabilityWeightedAverage(outputMetrics, tradeUp.outcomeProbabilities) { execCostPct(it) }
        val execCostScore = minOf(
            saturate(execCostA, EXEC_COST_THRESHOLD_PCT),
            saturate(execCostB, EXEC_COST_THRESHOLD_PCT),
            saturate(execCostOutput, EXEC_COST_THRESHOLD_PCT)
        )

        val volInput = maxOf(
            inputA.volatility7d ?: VOLATILITY_PLACEHOLDER,
            inputB.volatility7d ?: VOLATILITY_PLACEHOLDER
        )
        val volOutput = probabilityWeightedAverage(outputMetrics, tradeUp.outcomeProbabilities) {
            it.volatility7d ?: VOLATILITY_PLACEHOLDER
        }
        // Compounded variance across two sequential mandatory 7-day trade
        // locks (buy the inputs, wait; receive the output, wait again) --
        // NOT an average of the two, since the exposure windows don't
        // overlap. Both terms are in percentage points, so the result is too.
        val volatilityCombined7d = sqrt(volInput * volInput + volOutput * volOutput)
        val volScore = saturate(volatilityCombined7d, VOLATILITY_THRESHOLD)

        val liquidityA = inputA.liquidityScore ?: LIQUIDITY_PLACEHOLDER
        val liquidityB = inputB.liquidityScore ?: LIQUIDITY_PLACEHOLDER
        val liquidityOutput = probabilityWeightedAverage(outputMetrics, tradeUp.outcomeProbabilities) {
            it.liquidityScore ?: LIQUIDITY_PLACEHOLDER
        }
        val liquidityScore = minOf(liquidityA, liquidityB, liquidityOutput) / 100.0

        val depthGate = if (hasDepthFor(inputA, requiredQtyA) && hasDepthFor(inputB, requiredQtyB)) {
            DEPTH_GATE_OK
        } else {
            DEPTH_GATE_PENALTY
        }

        val weightedGeometricMean = weightedGeometricMean(
            listOf(
                roiScore to WEIGHT_ROI,
                profitChanceScore to WEIGHT_PROFIT_CHANCE,
                execCostScore to WEIGHT_EXEC_COST,
                volScore to WEIGHT_VOLATILITY,
                liquidityScore to WEIGHT_LIQUIDITY
            )
        )

        val rating = 100.0 * depthGate * weightedGeometricMean

        return RatingResult(
            rating = rating,
            depthGate = depthGate,
            volatilityCombined7d = volatilityCombined7d,
            roiScore = roiScore,
            profitChanceScore = profitChanceScore,
            execCostScore = execCostScore,
            volScore = volScore,
            liquidityScore = liquidityScore
        )
    }

    /**
     * True if openskin's price_impact object had a key covering [requiredQty]
     * units -- i.e. the "5" key for <=5 units, the "10" key otherwise (trade-up
     * legs range 1..9, so "10" safely covers 6..9 as the next available
     * granularity above "5"). A missing key means openskin's order book
     * couldn't fill that quantity at all when snapshotted.
     */
    private fun hasDepthFor(metrics: SkinMarketMetrics, requiredQty: Int): Boolean {
        return when {
            requiredQty <= 5 -> metrics.priceImpact5Pct != null
            else -> metrics.priceImpact10Pct != null
        }
    }

    private fun execCostPct(metrics: SkinMarketMetrics): Double {
        if (metrics.spreadPct == null && metrics.slippagePct == null && metrics.priceImpact5Pct == null) {
            return EXEC_COST_PLACEHOLDER_PCT
        }
        return (metrics.spreadPct ?: 0.0) + (metrics.slippagePct ?: 0.0) + (metrics.priceImpact5Pct ?: 0.0)
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /** Saturating [0,1] transform: value=0 -> 1.0, value=threshold -> 0.5. */
    private fun saturate(value: Double, threshold: Double): Double =
        (1.0 / (1.0 + value / threshold)).coerceIn(0.0, 1.0)

    private fun weightedGeometricMean(componentsAndWeights: List<Pair<Double, Double>>): Double {
        val totalWeight = componentsAndWeights.sumOf { it.second }
        val logSum = componentsAndWeights.sumOf { (value, weight) ->
            weight * kotlin.math.ln(value.coerceIn(1e-9, 1.0))
        }
        return exp(logSum / totalWeight)
    }

    private fun probabilityWeightedAverage(
        metrics: List<SkinMarketMetrics>,
        probabilities: List<Double>,
        selector: (SkinMarketMetrics) -> Double
    ): Double {
        if (metrics.isEmpty()) return EXEC_COST_PLACEHOLDER_PCT
        var weightedSum = 0.0
        var totalWeight = 0.0
        metrics.forEachIndexed { index, m ->
            val weight = probabilities.getOrElse(index) { 0.0 }
            weightedSum += selector(m) * weight
            totalWeight += weight
        }
        return if (totalWeight > 0.0) weightedSum / totalWeight else selector(metrics.first())
    }
}
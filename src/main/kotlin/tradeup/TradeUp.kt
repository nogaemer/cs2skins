package tradeup

import models.CSWear

/**
 * TradeUp bundles a chosen input (with its computed input cost and floats)
 * and the corresponding output distribution, exposing useful KPIs:
 * - inputCostWithDropChange: total cost of the 10 inputs at the optimized floats
 * - expectedReturn: average value of outcomes (uniform over output skins)
 * - profit: expectedReturn - inputCostWithDropChange
 * - roi: profit / inputCostWithDropChange
 * - profitChance: fraction of outcomes whose value >= inputCostWithDropChange
 * - profitPercentage: profitChance * 100
 */
class TradeUp(
    val input: TradeUpInput,
    val output: TradeUpOutput,
) {
    /** Total cost of inputs. Uses the precomputed CostsFloatInput stored on TradeUpInput. */
    val inputCost: Double by lazy { input.costsFloatInput?.costs ?: Double.NaN }
    val inputCostWithDropChange: Double by lazy { input.costsFloatInput?.costsWithDropChange ?: Double.NaN }

    /** Value of each possible outcome at its computed float bucket. */
    val outcomeValues: List<Double> by lazy {
        output.skins.mapNotNull { skin ->
            val f = skin.float ?: return@mapNotNull null
            val wear = CSWear.floatToCSWear(f)
            skin.price[wear]
        }
    }

    /** Expected return assuming uniform drop probability across output skins. */
    val expectedReturn: Double by lazy {
        // Build ballots per output skin: each input instance contributes its amount
        // as a ballot to every outcome that belongs to the same collection.
        val ballotsPerSkin = output.skins.map { skin ->
            val ballotsFromA = if (input.tradeUpInputComponentA.collectionId == skin.collectionId) input.tradeUpInputComponentA.amount else 0
            val ballotsFromB = if (input.tradeUpInputComponentB.collectionId == skin.collectionId) input.tradeUpInputComponentB.amount else 0
            Pair(skin, ballotsFromA + ballotsFromB)
        }

        val totalBallots = ballotsPerSkin.sumOf { it.second }
        if (totalBallots == 0) return@lazy 0.0

        var sum = 0.0
        for ((skin, ballots) in ballotsPerSkin) {
            if (ballots == 0) continue
            val f = skin.float ?: continue
            val wear = CSWear.floatToCSWear(f)
            val value = skin.price[wear] ?: 0.0
            val prob = ballots.toDouble() / totalBallots.toDouble()
            sum += value * prob
        }
        sum
    }


    /** Expected profit in absolute currency units. */
    val profitWithDropChange: Double by lazy { expectedReturn - inputCostWithDropChange }

    val profit: Double by lazy { expectedReturn - inputCost }

    /** Return on investment (unitless). */
    val roi: Double by lazy {
        if (inputCost.isFinite() && inputCost != 0.0) expectedReturn / inputCost else Double.NaN
    }

    val roiWithDropChange: Double by lazy {
        if (inputCostWithDropChange.isFinite() && inputCostWithDropChange != 0.0) expectedReturn / inputCostWithDropChange else Double.NaN
    }

    /** Probability that the outcome value is at least the input cost (0.0..1.0). Uses inputCostWithDropChange as threshold. */
    val profitChance: Double by lazy {
        val n = outcomeValues.size
        if (n == 0) 0.0 else outcomeValues.count { it >= inputCostWithDropChange }.toDouble() / n
    }

    /** Probability that the outcome value is at least the plain input cost (no drop-change adjustment, 0.0..1.0). */
    val profitChanceNoDropChange: Double by lazy {
        val n = outcomeValues.size
        if (n == 0) 0.0 else outcomeValues.count { it >= inputCost }.toDouble() / n
    }

    /** Same as profitChance but expressed in percent (0..100). */
    val profitPercentage: Double by lazy { profitChance * 100.0 }
}


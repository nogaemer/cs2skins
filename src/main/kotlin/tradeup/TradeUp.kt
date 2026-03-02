package tradeup

import models.CSWear

/**
 * TradeUp bundles a chosen input (with its computed input cost and floats)
 * and the corresponding output distribution, exposing useful KPIs:
 * - inputCostWithDropChange: total cost of the 10 inputs at the optimized floats
 * - expectedReturn: probability-weighted value of outcomes; P(skin) = (inputs from its collection / 10) / (output skins in that collection)
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
            skin.price[wear] ?: 0.0   // missing price counts as 0, not dropped
        }
    }

    /** Expected return using the real CS2 probability rule:
     *  P(skin s in collection C) = (inputs from C / total inputs) / (output skins in C) */
    val expectedReturn: Double by lazy {
        val totalInputs = input.tradeUpInputComponentA.amount + input.tradeUpInputComponentB.amount
        if (totalInputs == 0) return@lazy 0.0

        // How many output skins belong to each collection (the denominator per collection).
        val outputCountPerCollection = output.skins.groupingBy { it.collectionId }.eachCount()

        var sum = 0.0
        for (skin in output.skins) {
            val f = skin.float ?: continue
            val wear = CSWear.floatToCSWear(f)
            val value = skin.price[wear] ?: 0.0

            val inputsFromCollection =
                (if (input.tradeUpInputComponentA.collectionId == skin.collectionId) input.tradeUpInputComponentA.amount else 0) +
                (if (input.tradeUpInputComponentB.collectionId == skin.collectionId) input.tradeUpInputComponentB.amount else 0)
            val skinsInCollection = outputCountPerCollection[skin.collectionId] ?: 1

            val prob = inputsFromCollection.toDouble() / totalInputs.toDouble() / skinsInCollection.toDouble()
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

    /** Probability that the outcome value is at least the input cost (0.0..1.0). */
    val profitChance: Double by lazy {
        val n = outcomeValues.size
        if (n == 0) 0.0 else outcomeValues.count { it >= inputCostWithDropChange }.toDouble() / n
    }

    /** Same as profitChance but expressed in percent (0..100). */
    val profitPercentage: Double by lazy { profitChance * 100.0 }
}


package tradeup

import models.CSWear

/**
 * TradeUp bundles a chosen input (with its computed input cost and floats)
 * and the corresponding output distribution, exposing useful KPIs:
 * - inputCostWithDropChange: total cost of the 10 inputs at the optimized floats
 * - expectedReturn: probability-weighted value of outcomes;
 *   P(skin) = (inputs from its collection / 10) / (output skins in that collection)
 * - profit: expectedReturn - inputCostWithDropChange
 * - roi: profit / inputCostWithDropChange
 * - profitChance: fraction of outcomes whose value >= inputCostWithDropChange
 * - profitPercentage: profitChance * 100
 */
class TradeUp(
    val input: TradeUpInput,
    val output: TradeUpOutput,
) {
    val inputCost: Double by lazy { input.costsFloatInput?.costs ?: Double.NaN }
    val inputCostWithDropChange: Double by lazy { input.costsFloatInput?.costsWithDropChange ?: Double.NaN }

    val outcomeValues: List<Double> by lazy {
        output.skins.mapNotNull { skin ->
            val f = skin.float ?: return@mapNotNull null
            val wear = CSWear.floatToCSWear(f)
            skin.price[wear] ?: 0.0
        }
    }

    /** Probability of each output skin (same order/index as output.skins), using the real
     *  CS2 rule: P(skin) = (inputs from its collection / total inputs) / (output skins in that collection). */
    val outcomeProbabilities: List<Double> by lazy {
        val totalInputs = input.tradeUpInputComponentA.amount + input.tradeUpInputComponentB.amount
        if (totalInputs == 0) return@lazy output.skins.map { 0.0 }

        val outputCountPerCollection = output.skins.groupingBy { it.collectionId }.eachCount()

        output.skins.map { skin ->
            val inputsFromCollection =
                (if (input.tradeUpInputComponentA.collectionId == skin.collectionId) input.tradeUpInputComponentA.amount else 0) +
                        (if (input.tradeUpInputComponentB.collectionId == skin.collectionId) input.tradeUpInputComponentB.amount else 0)
            val skinsInCollection = outputCountPerCollection[skin.collectionId] ?: 1

            inputsFromCollection.toDouble() / totalInputs.toDouble() / skinsInCollection.toDouble()
        }
    }

    val expectedReturn: Double by lazy {
        output.skins.zip(outcomeProbabilities).sumOf { (skin, probability) ->
            val f = skin.float ?: return@sumOf 0.0
            val wear = CSWear.floatToCSWear(f)
            val value = skin.price[wear] ?: 0.0
            value * probability
        }
    }

    val profitWithDropChange: Double by lazy { expectedReturn - inputCostWithDropChange }
    val profit: Double by lazy { expectedReturn - inputCost }

    val roi: Double by lazy {
        if (inputCost.isFinite() && inputCost != 0.0) expectedReturn / inputCost else Double.NaN
    }

    val roiWithDropChange: Double by lazy {
        if (inputCostWithDropChange.isFinite() && inputCostWithDropChange != 0.0) expectedReturn / inputCostWithDropChange else Double.NaN
    }

    val profitChance: Double by lazy {
        val n = outcomeValues.size
        if (n == 0) 0.0 else outcomeValues.count { it >= inputCostWithDropChange }.toDouble() / n
    }

    val profitChanceNoDropChange: Double by lazy {
        val n = outcomeValues.size
        if (n == 0) 0.0 else outcomeValues.count { it >= inputCost }.toDouble() / n
    }

    val profitPercentage: Double by lazy { profitChance * 100.0 }
}
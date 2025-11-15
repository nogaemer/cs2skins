package models

import tradeup.ProbabilityLinear

data class Skin(
    val name: String,
    val price: MutableMap<CSWear, Double>,
    val collectionId: String,
    val minFloatCap: Double,
    val maxFloatCap: Double,
    val float: Double? = null,
    val floatCapDifference: Double = maxFloatCap - minFloatCap,
    val linearProbabilities: MutableMap<CSWear, MutableList<ProbabilityLinear>> = mutableMapOf()
)

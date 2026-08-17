package de.nogaemer.cs2skinsv2.catalog.model

import de.nogaemer.cs2skinsv2.tradeup.model.ProbabilityLinear

data class Skin(
    val name: String,
    val price: MutableMap<CSWear, Double>,
    val collectionId: String,
    val minFloatCap: Double,
    val maxFloatCap: Double,
    val itemId: Long? = null,
    val float: Double? = null,
    val floatCapDifference: Double = maxFloatCap - minFloatCap,
    val linearProbabilities: MutableMap<CSWear, MutableList<ProbabilityLinear>> = mutableMapOf()
)
package de.nogaemer.cs2skinsv2.tradeup.dto

data class TradeUpInputRefDto(
    val skinId: Long,
    val name: String,
    val imageUrl: String?,
    val count: Int,
    val wearBucket: String
)

data class TradeUpOutcomeRefDto(
    val skinId: Long,
    val name: String,
    val imageUrl: String?,
    val probability: Double
)

data class TradeUpSummaryDto(
    val recipeId: String, // UUID as string
    val rating: Double,
    val roi: Double,
    val roiWithDropChange: Double,
    val profitChance: Double,
    val inputCost: Double,
    val inputCostWithDropChange: Double,
    val expectedValue: Double,
    val profit: Double,
    val profitWithDropChange: Double,
    val depthGate: Double,
    val isBestForPair: Boolean,
    val outcomeCount: Int,
    val inputs: List<TradeUpInputRefDto>,
    val topOutcome: TradeUpOutcomeRefDto?
)

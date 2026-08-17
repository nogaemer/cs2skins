package de.nogaemer.cs2skinsv2.tradeup.dto

import java.time.Instant


data class TradeUpRatingBreakdownDto(
    val roiScore: Double,
    val profitChanceScore: Double,
    val execCostScore: Double,
    val volScore: Double,
    val liquidityScore: Double,
    val depthGate: Double
)

data class TradeUpRarityRefDto(
    val id: Short,
    val name: String,
    val colorHex: String?
)

data class TradeUpDetailInputDto(
    val skinId: Long,
    val name: String,
    val imageUrl: String?,
    val count: Int,
    val wearBucket: String,
    val float: Double,
    val currentPrice: Double?
)

data class TradeUpDetailOutcomeDto(
    val skinId: Long,
    val name: String,
    val imageUrl: String?,
    val outputFloat: Double,
    val outputWearBucket: String,
    val probability: Double,
    val price: Double,
    val expectedContribution: Double
)

data class TradeUpDetailDto(
    val recipeId: String,
    val inputRarity: TradeUpRarityRefDto?,
    val outputRarity: TradeUpRarityRefDto?,
    val wearBucket: String,
    val allowStattrak: Boolean,
    val rating: Double,
    val isBestForPair: Boolean,
    val ratingBreakdown: TradeUpRatingBreakdownDto,
    val roi: Double,
    val roiWithDropChange: Double,
    val profitChance: Double,
    val inputCost: Double,
    val inputCostWithDropChange: Double,
    val expectedValue: Double,
    val profit: Double,
    val profitWithDropChange: Double,
    val depthGate: Double,
    val volatilityCombined7d: Double,
    val algorithmVersion: String,
    val snapshotAt: Instant,
    val inputs: List<TradeUpDetailInputDto>,
    val outcomes: List<TradeUpDetailOutcomeDto>,
    val computedAt: Instant,
)

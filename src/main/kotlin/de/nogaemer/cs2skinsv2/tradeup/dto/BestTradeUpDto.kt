package de.nogaemer.cs2skinsv2.tradeup.dto

import java.time.OffsetDateTime

data class BestTradeUpRecipeDto(
    val recipeId: String,
    val rating: Double,
    val roiWithDropChange: Double,
    val profitChance: Double,
    val computedAt: OffsetDateTime
)

data class BestTradeUpResponseDto(
    val found: Boolean,
    val recipe: BestTradeUpRecipeDto?
)
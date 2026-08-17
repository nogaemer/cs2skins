package de.nogaemer.cs2skinsv2.tradeup.dto

import java.time.Instant

data class TradeUpHistoryPointDto(
    val runId: Long,
    val snapshotAt: Instant,
    val rating: Double,
    val roiWithDropChange: Double,
    val profitChance: Double,
    val inputCost: Double
)

data class TradeUpHistoryResponseDto(
    val recipeId: String,
    val window: String,
    val points: List<TradeUpHistoryPointDto>,
    val note: String
)
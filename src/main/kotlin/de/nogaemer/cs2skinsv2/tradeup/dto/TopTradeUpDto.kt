package de.nogaemer.cs2skinsv2.tradeup.dto

import java.time.OffsetDateTime

data class TopTradeUpInputDto(
    val skinId: Long,
    val name: String,
    val imageUrl: String?,
    val count: Int
)

data class TopTradeUpDto(
    val recipeId: String,
    val rating: Double,
    val roiWithDropChange: Double,
    val profitChance: Double,
    val inputs: List<TopTradeUpInputDto>,
    val computedAt: OffsetDateTime
)

data class TopTradeUpListResponse(
    val content: List<TopTradeUpDto>
)
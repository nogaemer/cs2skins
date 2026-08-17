package de.nogaemer.cs2skinsv2.catalog.dto

import java.time.Instant

data class PriceHistoryPointDto(
    val observedAt: Instant,
    val averagePrice: Double,
    val volume24h: Int,
    val liquidityScore: Float
)

data class PriceHistoryResponse(
    val itemId: Long,
    val wearBucket: String,
    val window: String,
    val points: List<PriceHistoryPointDto>
)

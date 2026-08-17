package de.nogaemer.cs2skinsv2.catalog.dto

import java.time.OffsetDateTime

data class SkinCollectionRefDto(val id: Long, val name: String)
data class SkinRarityRefDto(val id: Short, val name: String, val colorHex: String?)

data class SkinPriceByWearDto(
    val wearBucket: String,
    val averagePrice: Double,
    val buyPrice: Double?,
    val sellPrice: Double?,
    val liquidityScore: Double?,
    val spreadPct: Double?,
    val slippagePct: Double?,
    // Nullable and deliberately NOT defaulted to 0 -- null means openskin's order book
    // couldn't fill that quantity at snapshot time, a real liquidity signal, not missing data.
    val priceImpact5Pct: Double?,
    val priceImpact10Pct: Double?,
    val volatility1d: Double?,
    val volatility7d: Double?,
    val observedAt: OffsetDateTime
)

data class SkinDetailDto(
    val id: Long,
    val name: String,
    val marketHashName: String,
    val collection: SkinCollectionRefDto?,
    val rarity: SkinRarityRefDto?,
    val weaponName: String?,
    val minFloat: Double,
    val maxFloat: Double,
    val stattrak: Boolean,
    val souvenir: Boolean,
    val imageUrl: String?,
    val pricesByWear: List<SkinPriceByWearDto>
)

package de.nogaemer.cs2skinsv2.catalog.dto

data class SkinCurrentPriceDto(
    val wearBucket: String,
    val averagePrice: Double,
    val liquidityScore: Double?
)

data class SkinSummaryDto(
    val id: Long,
    val name: String,
    val collectionId: Long?,
    val collectionName: String?,
    val rarityId: Short?,
    val rarityName: String?,
    val rarityColorHex: String?,
    val imageUrl: String?,
    val stattrak: Boolean,
    val souvenir: Boolean,
    val currentPrice: SkinCurrentPriceDto?
)

package de.nogaemer.cs2skinsv2.catalog.dto

data class SkinVariantDto(
    val type: String, // "normal" | "stattrak" | "souvenir"
    val itemId: Long,
    val pricesByWear: List<SkinPriceByWearDto>
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
    val variants: List<SkinVariantDto>
)
package de.nogaemer.cs2skinsv2.catalog.dto

data class CollectionItemDto(
    val id: Long,
    val name: String,
    val imageUrl: String?
)

data class CollectionRarityGroupDto(
    val rarityId: Short,
    val rarityName: String,
    val rarityColorHex: String?,
    val items: List<CollectionItemDto>
)

data class CollectionDetailDto(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val itemsByRarity: List<CollectionRarityGroupDto>
)

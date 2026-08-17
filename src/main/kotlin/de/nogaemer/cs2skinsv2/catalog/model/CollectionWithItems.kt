package de.nogaemer.cs2skinsv2.catalog.model

data class CollectionWithItems(
    val collectionId: Long,
    val name: String,
    val imageUrl: String?,
    val itemsByRarity: Map<String, List<Item>>
)
package models

import database.postgres.Item

data class CollectionWithItems(
    val collectionId: Long,
    val name: String,
    val imageUrl: String?,
    val itemsByRarity: Map<String, List<Item>>
)
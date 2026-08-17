package de.nogaemer.cs2skinsv2.catalog.dto

data class CollectionSummaryDto(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val itemCount: Int
)

data class CollectionListResponse(
    val collections: List<CollectionSummaryDto>
)

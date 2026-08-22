package de.nogaemer.cs2skinsv2.catalog.controller

import de.nogaemer.cs2skinsv2.catalog.dto.*
import de.nogaemer.cs2skinsv2.catalog.repository.CatalogRepository
import de.nogaemer.cs2skinsv2.common.exception.NotFoundException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/collections")
class CollectionController(
    private val catalogRepository: CatalogRepository
) {

    @GetMapping
    fun listCollections(): CollectionListResponse {
        val collections = catalogRepository.findAllCollections()
        val itemCounts = catalogRepository.countItemsByCollection()

        val dtos = collections.map { c ->
            CollectionSummaryDto(
                id = c.id.toString(),
                name = c.name,
                imageUrl = c.imageUrl,
                itemCount = itemCounts[c.id] ?: 0
            )
        }
        return CollectionListResponse(dtos)
    }

    @GetMapping("/{collectionId}")
    fun getCollection(@PathVariable collectionId: Long): CollectionDetailDto {
        val collection = catalogRepository.findCollectionById(collectionId)
            ?: throw NotFoundException("No collection found with id $collectionId")

        // Non-StatTrak/non-Souvenir items only -- matches countItemsByCollection's
        // definition of a collection's "real" skin list used in the list endpoint above.
        val items = catalogRepository.findItemsByCollection(collectionId, stattrak = false, souvenir = false)
        val raritiesById = catalogRepository.findAllRaritiesOrdered().associateBy { it.id }

        val itemsByRarity = items
            .filter { it.rarityId != null }
            .groupBy { it.rarityId!! }
            .toSortedMap(compareBy { raritiesById[it]?.sortOrder ?: Short.MAX_VALUE })
            .map { (rarityId, rarityItems) ->
                val rarity = raritiesById[rarityId]
                CollectionRarityGroupDto(
                    rarityId = rarityId,
                    rarityName = rarity?.name ?: "Unknown",
                    rarityColorHex = rarity?.colorHex,
                    items = rarityItems.map { CollectionItemDto(it.id, it.name, it.imageUrl) }
                )
            }

        return CollectionDetailDto(
            id = collection.id,
            name = collection.name,
            imageUrl = collection.imageUrl,
            itemsByRarity = itemsByRarity
        )
    }
}

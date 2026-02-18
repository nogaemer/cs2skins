package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.CollectionResponse
import com.nogaemer.cs2skins.dto.CollectionWithSkinsResponse
import database.Collection
import database.CollectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class CollectionService(
    private val collectionRepository: CollectionRepository = CollectionRepository(),
    private val skinService: SkinService
) {

    suspend fun getAllCollections(): List<CollectionResponse> = withContext(Dispatchers.IO) {
        val collections = collectionRepository.findAll()
        collections.map { mapToCollectionResponse(it) }
    }

    suspend fun getCollectionById(collectionId: String): CollectionResponse? = withContext(Dispatchers.IO) {
        val collection = collectionRepository.findById(collectionId)
        collection?.let { mapToCollectionResponse(it) }
    }

    suspend fun getCollectionWithSkins(collectionId: String, stattrak: Boolean = false): CollectionWithSkinsResponse? = withContext(Dispatchers.IO) {
        val collection = collectionRepository.findById(collectionId) ?: return@withContext null
        val skins = skinService.getSkinsByCollection(collectionId, stattrak)
        
        CollectionWithSkinsResponse(
            collectionId = collection.collectionId,
            name = collection.name,
            image = collection.image,
            skins = skins
        )
    }

    private fun mapToCollectionResponse(collection: Collection): CollectionResponse {
        return CollectionResponse(
            collectionId = collection.collectionId,
            name = collection.name,
            image = collection.image
        )
    }
}

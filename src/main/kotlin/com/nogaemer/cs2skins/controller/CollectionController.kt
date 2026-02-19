package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.CollectionResponse
import com.nogaemer.cs2skins.dto.CollectionWithSkinsResponse
import com.nogaemer.cs2skins.service.CollectionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/collections")
class CollectionController(private val collectionService: CollectionService) {

    @GetMapping
    suspend fun getAllCollections(): ResponseEntity<List<CollectionResponse>> {
        val collections = collectionService.getAllCollections()
        return ResponseEntity.ok(collections)
    }

    @GetMapping("/{collectionId}")
    suspend fun getCollectionById(@PathVariable collectionId: String): ResponseEntity<CollectionResponse> {
        val collection = collectionService.getCollectionById(collectionId)
        return collection?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @GetMapping("/{collectionId}/skins")
    suspend fun getCollectionWithSkins(
        @PathVariable collectionId: String,
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<CollectionWithSkinsResponse> {
        val collection = collectionService.getCollectionWithSkins(collectionId, stattrak)
        return collection?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }
}

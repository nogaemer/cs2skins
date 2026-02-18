package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.CollectionResponse
import com.nogaemer.cs2skins.dto.CollectionWithSkinsResponse
import com.nogaemer.cs2skins.service.CollectionService
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/collections")
class CollectionController(private val collectionService: CollectionService) {

    @GetMapping
    fun getAllCollections(): ResponseEntity<List<CollectionResponse>> = runBlocking {
        val collections = collectionService.getAllCollections()
        ResponseEntity.ok(collections)
    }

    @GetMapping("/{collectionId}")
    fun getCollectionById(@PathVariable collectionId: String): ResponseEntity<CollectionResponse> = runBlocking {
        val collection = collectionService.getCollectionById(collectionId)
        collection?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @GetMapping("/{collectionId}/skins")
    fun getCollectionWithSkins(
        @PathVariable collectionId: String,
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<CollectionWithSkinsResponse> = runBlocking {
        val collection = collectionService.getCollectionWithSkins(collectionId, stattrak)
        collection?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }
}

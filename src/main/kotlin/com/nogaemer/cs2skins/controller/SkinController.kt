package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.SkinFilterRequest
import com.nogaemer.cs2skins.dto.SkinResponse
import com.nogaemer.cs2skins.service.SkinService
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/skins")
class SkinController(private val skinService: SkinService) {

    @GetMapping
    fun getAllSkins(): ResponseEntity<List<SkinResponse>> = runBlocking {
        val skins = skinService.getAllSkins()
        ResponseEntity.ok(skins)
    }

    @GetMapping("/{skinId}")
    fun getSkinById(@PathVariable skinId: String): ResponseEntity<SkinResponse> = runBlocking {
        val skin = skinService.getSkinById(skinId)
        skin?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/search")
    fun searchSkins(@RequestBody filter: SkinFilterRequest): ResponseEntity<List<SkinResponse>> = runBlocking {
        val skins = skinService.searchSkins(filter)
        ResponseEntity.ok(skins)
    }

    @GetMapping("/weapon/{weaponId}")
    fun getSkinsByWeapon(@PathVariable weaponId: String): ResponseEntity<List<SkinResponse>> = runBlocking {
        val skins = skinService.getSkinsByWeapon(weaponId)
        ResponseEntity.ok(skins)
    }

    @GetMapping("/rarity/{rarityId}")
    fun getSkinsByRarity(@PathVariable rarityId: String): ResponseEntity<List<SkinResponse>> = runBlocking {
        val skins = skinService.getSkinsByRarity(rarityId)
        ResponseEntity.ok(skins)
    }

    @GetMapping("/collection/{collectionId}")
    fun getSkinsByCollection(
        @PathVariable collectionId: String,
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<List<SkinResponse>> = runBlocking {
        val skins = skinService.getSkinsByCollection(collectionId, stattrak)
        ResponseEntity.ok(skins)
    }
}

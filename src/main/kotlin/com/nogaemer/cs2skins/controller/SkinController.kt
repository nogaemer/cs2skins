package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.SkinFilterRequest
import com.nogaemer.cs2skins.dto.SkinResponse
import com.nogaemer.cs2skins.service.SkinService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/skins")
class SkinController(private val skinService: SkinService) {

    @GetMapping
    suspend fun getAllSkins(): ResponseEntity<List<SkinResponse>> {
        val skins = skinService.getAllSkins()
        return ResponseEntity.ok(skins)
    }

    @GetMapping("/{skinId}")
    suspend fun getSkinById(@PathVariable skinId: String): ResponseEntity<SkinResponse> {
        val skin = skinService.getSkinById(skinId)
        return skin?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/search")
    suspend fun searchSkins(@RequestBody filter: SkinFilterRequest): ResponseEntity<List<SkinResponse>> {
        val skins = skinService.searchSkins(filter)
        return ResponseEntity.ok(skins)
    }

    @GetMapping("/weapon/{weaponId}")
    suspend fun getSkinsByWeapon(@PathVariable weaponId: String): ResponseEntity<List<SkinResponse>> {
        val skins = skinService.getSkinsByWeapon(weaponId)
        return ResponseEntity.ok(skins)
    }

    @GetMapping("/rarity/{rarityId}")
    suspend fun getSkinsByRarity(@PathVariable rarityId: String): ResponseEntity<List<SkinResponse>> {
        val skins = skinService.getSkinsByRarity(rarityId)
        return ResponseEntity.ok(skins)
    }

    @GetMapping("/collection/{collectionId}")
    suspend fun getSkinsByCollection(
        @PathVariable collectionId: String,
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<List<SkinResponse>> {
        val skins = skinService.getSkinsByCollection(collectionId, stattrak)
        return ResponseEntity.ok(skins)
    }
}

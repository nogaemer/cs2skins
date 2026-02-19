package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.SkinFilterRequest
import com.nogaemer.cs2skins.dto.SkinPriceHistoryResponse
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

    /**
     * Returns price history for a specific skin+wear combination.
     *
     * @param skinId  the skin identifier
     * @param wearId  the wear condition identifier
     * @param from    start of range, epoch milliseconds (default: 30 days ago)
     * @param to      end of range, epoch milliseconds (default: now)
     */
    @GetMapping("/{skinId}/price-history/{wearId}")
    suspend fun getSkinPriceHistory(
        @PathVariable skinId: String,
        @PathVariable wearId: String,
        @RequestParam(defaultValue = "0") from: Long,
        @RequestParam(defaultValue = "0") to: Long
    ): ResponseEntity<List<SkinPriceHistoryResponse>> {
        val now = System.currentTimeMillis()
        val fromMs = if (from > 0L) from else null
        val toMs = if (to > 0L) to else null
        val history = skinService.getSkinPriceHistory(skinId, wearId, fromMs, toMs)
        return ResponseEntity.ok(history)
    }
}


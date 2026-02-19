package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.PageResponse
import com.nogaemer.cs2skins.dto.TradeUpFilterRequest
import com.nogaemer.cs2skins.dto.TradeUpResultResponse
import com.nogaemer.cs2skins.service.TradeUpService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tradeups")
class TradeUpController(private val tradeUpService: TradeUpService) {

    @GetMapping
    suspend fun getAllTradeUps(): ResponseEntity<List<TradeUpResultResponse>> {
        val tradeUps = tradeUpService.getAllTradeUps()
        return ResponseEntity.ok(tradeUps)
    }

    @GetMapping("/{id}")
    suspend fun getTradeUpById(@PathVariable id: Int): ResponseEntity<TradeUpResultResponse> {
        val tradeUp = tradeUpService.getTradeUpById(id)
        return tradeUp?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/filter")
    suspend fun filterTradeUps(@RequestBody filter: TradeUpFilterRequest): ResponseEntity<PageResponse<TradeUpResultResponse>> {
        val tradeUps = tradeUpService.filterTradeUps(filter)
        return ResponseEntity.ok(tradeUps)
    }

    @DeleteMapping
    suspend fun deleteAllTradeUps(): ResponseEntity<Map<String, Any>> {
        val count = tradeUpService.deleteAllTradeUps()
        return ResponseEntity.ok(mapOf("deleted" to count, "message" to "All trade-ups deleted successfully"))
    }
}

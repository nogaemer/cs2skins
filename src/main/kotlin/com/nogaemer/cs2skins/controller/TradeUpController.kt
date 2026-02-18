package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.TradeUpFilterRequest
import com.nogaemer.cs2skins.dto.TradeUpResultResponse
import com.nogaemer.cs2skins.service.TradeUpService
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tradeups")
class TradeUpController(private val tradeUpService: TradeUpService) {

    @GetMapping
    fun getAllTradeUps(): ResponseEntity<List<TradeUpResultResponse>> = runBlocking {
        val tradeUps = tradeUpService.getAllTradeUps()
        ResponseEntity.ok(tradeUps)
    }

    @GetMapping("/{id}")
    fun getTradeUpById(@PathVariable id: Int): ResponseEntity<TradeUpResultResponse> = runBlocking {
        val tradeUp = tradeUpService.getTradeUpById(id)
        tradeUp?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/filter")
    fun filterTradeUps(@RequestBody filter: TradeUpFilterRequest): ResponseEntity<List<TradeUpResultResponse>> = runBlocking {
        val tradeUps = tradeUpService.filterTradeUps(filter)
        ResponseEntity.ok(tradeUps)
    }

    @DeleteMapping
    fun deleteAllTradeUps(): ResponseEntity<Map<String, Any>> = runBlocking {
        val count = tradeUpService.deleteAllTradeUps()
        ResponseEntity.ok(mapOf("deleted" to count, "message" to "All trade-ups deleted successfully"))
    }
}

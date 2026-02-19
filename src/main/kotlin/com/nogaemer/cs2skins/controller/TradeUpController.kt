package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.PageResponse
import com.nogaemer.cs2skins.dto.TradeUpFilterRequest
import com.nogaemer.cs2skins.dto.TradeUpHistoryPoint
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

    /**
     * Returns time-bucketed historical ROI/profit snapshots for a trade-up.
     *
     * @param id       trade-up master id
     * @param from     start of range, epoch milliseconds (default: 30 days ago)
     * @param to       end of range, epoch milliseconds (default: now)
     * @param bucket   bucket width in milliseconds (default: 86400000 = 1 day)
     */
    @GetMapping("/{id}/history")
    suspend fun getTradeUpHistory(
        @PathVariable id: Int,
        @RequestParam(defaultValue = "0") from: Long,
        @RequestParam(defaultValue = "0") to: Long,
        @RequestParam(defaultValue = "86400000") bucket: Long
    ): ResponseEntity<List<TradeUpHistoryPoint>> {
        val now = System.currentTimeMillis()
        val fromMs = if (from > 0L) from else now - 30L * 24 * 60 * 60 * 1000
        val toMs = if (to > 0L) to else now
        val history = tradeUpService.getTradeupHistory(id, fromMs, toMs, bucket)
        return ResponseEntity.ok(history)
    }

    @DeleteMapping
    suspend fun deleteAllTradeUps(): ResponseEntity<Map<String, Any>> {
        val count = tradeUpService.deleteAllTradeUps()
        return ResponseEntity.ok(mapOf("deleted" to count, "message" to "All trade-ups deleted successfully"))
    }
}


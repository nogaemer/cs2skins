package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.*
import com.nogaemer.cs2skins.service.AsyncJobService
import com.nogaemer.cs2skins.service.TradeUpService
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/api/tradeups")
class TradeUpController(
    private val tradeUpService: TradeUpService,
    private val asyncJobService: AsyncJobService
) {

    private val generateMastersJobRunning = AtomicBoolean(false)
    private val calculatePricesJobRunning = AtomicBoolean(false)

    @GetMapping("/test")
    fun test(): ResponseEntity<String> {
        runBlocking {
            tradeUpService.test()
        }
        return ResponseEntity.ok("Test successful")
    }

    @PostMapping("/generate-masters")
    fun generateMasters(
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<JobStatusResponse> {
        if (!generateMastersJobRunning.compareAndSet(false, true)) {
            return ResponseEntity.ok(JobStatusResponse("running", "Generate masters job is already running"))
        }

        asyncJobService.generateMastersAsync(stattrak)
            .whenComplete { _, _ -> generateMastersJobRunning.set(false) }

        return ResponseEntity.ok(JobStatusResponse("started", "Generate masters job started (stattrak: $stattrak)"))
    }

    @PostMapping("/calculate-prices")
    fun calculatePrices(
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<JobStatusResponse> {
        if (!calculatePricesJobRunning.compareAndSet(false, true)) {
            return ResponseEntity.ok(JobStatusResponse("running", "Calculate prices job is already running"))
        }

        asyncJobService.calculatePricesAsync(stattrak)
            .whenComplete { _, _ -> calculatePricesJobRunning.set(false) }

        return ResponseEntity.ok(JobStatusResponse("started", "Calculate prices job started (stattrak: $stattrak)"))
    }

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
     * @param id        trade-up master id
     * @param from      start of range, epoch milliseconds (default: 30 days ago)
     * @param to        end of range, epoch milliseconds (default: now)
     * @param bucket    bucket width in milliseconds (default: 86400000 = 1 day)
     * @param maxPoints maximum number of buckets to return (1..10000, default: 1000).
     *                  The effective start is clamped so at most this many points are produced.
     */
    @GetMapping("/{id}/history")
    suspend fun getTradeUpHistory(
        @PathVariable id: Int,
        @RequestParam(defaultValue = "0") from: Long,
        @RequestParam(defaultValue = "0") to: Long,
        @RequestParam(defaultValue = "86400000") bucket: Long,
        @RequestParam(defaultValue = "1000") maxPoints: Int
    ): ResponseEntity<List<TradeUpHistoryPoint>> {
        val now = System.currentTimeMillis()
        val toMs = if (to > 0L) to else now
        val rawFrom = if (from > 0L) from else now - 30L * 24 * 60 * 60 * 1000
        // Validate bucket width; fall back to 1 ms to avoid division-by-zero or negative offsets
        val safeBucket = if (bucket > 0L) bucket else 1L
        // Clamp the effective start so the number of returned buckets is bounded
        val clampedMax = maxPoints.coerceIn(1, 10_000)
        val effectiveFrom = maxOf(rawFrom, toMs - safeBucket * clampedMax)
        val history = tradeUpService.getTradeupHistory(id, effectiveFrom, toMs, safeBucket)
        return ResponseEntity.ok(history)
    }

    /**
     * Returns risk summary metrics for a trade-up over a time window.
     *
     * @param tradeupId  trade-up master id (path variable)
     * @param from       start of range, epoch milliseconds (default: 30 days ago)
     * @param to         end of range, epoch milliseconds (default: now)
     */
    @GetMapping("/{tradeupId}/risk")
    suspend fun getTradeUpRisk(
        @PathVariable tradeupId: Int,
        @RequestParam(defaultValue = "0") from: Long,
        @RequestParam(defaultValue = "0") to: Long,
    ): ResponseEntity<TradeUpRiskSummaryResponse> {
        val now = System.currentTimeMillis()
        val toMs = if (to > 0L) to else now
        val fromMs = if (from > 0L) from else now - 30L * 24 * 60 * 60 * 1000
        val summary = tradeUpService.getTradeupRiskSummary(tradeupId, fromMs, toMs)
        return ResponseEntity.ok(summary)
    }

    @DeleteMapping
    suspend fun deleteAllTradeUps(): ResponseEntity<Map<String, Any>> {
        val count = tradeUpService.deleteAllTradeUps()
        return ResponseEntity.ok(mapOf("deleted" to count, "message" to "All trade-ups deleted successfully"))
    }
}


package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.*
import com.nogaemer.cs2skins.service.AsyncJobService
import com.nogaemer.cs2skins.service.TradeUpService
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
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
     * When [bucket] >= 86 400 000 ms (1 day), results are read from the
     * `tradeup_daily` continuous aggregate for fast, index-only access.
     * Smaller buckets fall back to the raw `tradeup_snapshots` hypertable.
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
        val safeBucket = if (bucket > 0L) bucket else 1L
        val clampedMax = maxPoints.coerceIn(1, 10_000)
        val effectiveFrom = maxOf(rawFrom, toMs - safeBucket * clampedMax)

        val history = if (safeBucket == ONE_DAY_MS) {
            // Use continuous aggregate only for exact 1-day buckets; coarser
            // widths (e.g. 7d, 30d) fall back to raw snapshots so the caller's
            // requested bucket width is applied correctly in-application.
            tradeUpService.getTradeupHistoryAggregate(id, effectiveFrom, toMs, clampedMax)
        } else {
            tradeUpService.getTradeupHistory(id, effectiveFrom, toMs, safeBucket)
        }
        return ResponseEntity.ok(history)
    }

    /**
     * Returns an aggregated risk summary for a trade-up over the given time window.
     *
     * Risk metric fields ([probProfit], [variance], p05/p50/p95) are null when no
     * snapshot in the window carries risk-metric data (requires migration 006).
     *
     * @param id   trade-up master id
     * @param from start of range, epoch milliseconds (default: 30 days ago)
     * @param to   end of range, epoch milliseconds (default: now)
     */
    @GetMapping("/{id}/risk")
    suspend fun getTradeupRisk(
        @PathVariable id: Int,
        @RequestParam(defaultValue = "0") from: Long,
        @RequestParam(defaultValue = "0") to: Long
    ): ResponseEntity<TradeUpRiskResponse> {
        val now = System.currentTimeMillis()
        val toMs   = if (to   > 0L) to   else now
        val fromMs = if (from > 0L) from else now - 30L * 24 * 60 * 60 * 1000
        val risk = tradeUpService.getTradeupRisk(id, fromMs, toMs)
        return ResponseEntity.ok(risk)
    }

    /**
     * Returns the top-N trade-ups ranked by ROI or profit within a time window.
     *
     * When [bucket] is "day" the query reads from the `tradeup_daily` continuous
     * aggregate (fast, avoids full hypertable scans).  Any other value falls back
     * to the raw `tradeup_snapshots` hypertable with a time-range filter.
     *
     * @param from        start of range, epoch milliseconds (default: 30 days ago)
     * @param to          end of range, epoch milliseconds (default: now)
     * @param limit       max results, 1–200 (default: 10)
     * @param sort        ranking field: "roi" or "profit" (default: "roi")
     * @param bucket      aggregation granularity: "day" uses the aggregate; other values use raw data (default: "day")
     * @param stattrak    optional filter: true = StatTrak only, false = non-StatTrak only
     * @param rarity      optional rarity_id filter (e.g. "rarity-classified")
     * @param collections optional comma-separated collection IDs to filter by
     */
    @GetMapping("/top")
    suspend fun getTopTradeups(
        @RequestParam(defaultValue = "0") from: Long,
        @RequestParam(defaultValue = "0") to: Long,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "roi") sort: String,
        @RequestParam(defaultValue = "day") bucket: String,
        @RequestParam(required = false) stattrak: Boolean?,
        @RequestParam(required = false) rarity: String?,
        @RequestParam(required = false) collections: String?
    ): ResponseEntity<TopTradeupResponse> {
        if (sort !in ALLOWED_SORT_FIELDS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid sort field '$sort'. Allowed values: ${ALLOWED_SORT_FIELDS.joinToString()}"
            )
        }
        val now    = System.currentTimeMillis()
        val toMs   = if (to   > 0L) to   else now
        val fromMs = if (from > 0L) from else now - 30L * 24 * 60 * 60 * 1000
        val result = tradeUpService.getTopTradeups(
            fromMs      = fromMs,
            toMs        = toMs,
            limit       = limit,
            sortBy      = sort,
            bucket      = bucket,
            stattrak    = stattrak,
            rarity      = rarity,
            collections = collections
        )
        return ResponseEntity.ok(result)
    }

    @DeleteMapping
    suspend fun deleteAllTradeUps(): ResponseEntity<Map<String, Any>> {
        val count = tradeUpService.deleteAllTradeUps()
        return ResponseEntity.ok(mapOf("deleted" to count, "message" to "All trade-ups deleted successfully"))
    }

    companion object {
        private const val ONE_DAY_MS = 86_400_000L
        private val ALLOWED_SORT_FIELDS = setOf("roi", "profit")
    }
}


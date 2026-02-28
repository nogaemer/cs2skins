package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.BucketedPriceResponse
import com.nogaemer.cs2skins.dto.LatestPriceResponse
import com.nogaemer.cs2skins.dto.RawPriceHistoryResponse
import com.nogaemer.cs2skins.service.PriceService
import database.PriceHistoryParams
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * REST controller exposing price-history endpoints.
 *
 *   GET /api/prices/skins/{skinId}/latest?source=&currency=
 *   GET /api/prices/skins/{skinId}/history?wearId=&from=&to=&bucket=&limit=&offset=
 */
@RestController
@RequestMapping("/api/prices/skins")
class PriceController(private val priceService: PriceService) {

    /**
     * Returns the latest known price for every wear/source/currency combination for [skinId].
     * Optional [source] and [currency] query parameters narrow the result set.
     */
    @GetMapping("/{skinId}/latest")
    suspend fun getLatestPrices(
        @PathVariable skinId: String,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) currency: String?
    ): ResponseEntity<List<LatestPriceResponse>> {
        val prices = priceService.getLatestPrices(skinId, source, currency)
        return ResponseEntity.ok(prices)
    }

    /**
     * Returns price history for [skinId].
     *
     * When [bucket] is provided the response contains TimescaleDB `time_bucket()` aggregates
     * (avg/min/max per bucket); otherwise raw snapshot rows are returned with limit/offset
     * pagination.
     *
     * @param wearId   optional wear-condition filter (e.g. "factory_new")
     * @param from     range start in ISO 8601 / RFC 3339 format; defaults to now − 7 days
     * @param to       range end in ISO 8601 / RFC 3339 format; defaults to now
     * @param source   optional source-name filter (e.g. "steam")
     * @param currency optional currency-code filter (e.g. "USD")
     * @param bucket   time-bucket width; must be one of: 1h, 6h, 1d, 7d, 30d
     * @param limit    maximum rows/buckets to return (capped at 1000 raw / 500 bucketed)
     * @param offset   zero-based row offset for raw queries (ignored when bucket is set)
     */
    @GetMapping("/{skinId}/history")
    suspend fun getPriceHistory(
        @PathVariable skinId: String,
        @RequestParam(required = false) wearId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) currency: String?,
        @RequestParam(required = false) bucket: String?,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ResponseEntity<*> {
        if (bucket != null && bucket !in ALLOWED_BUCKETS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid bucket '$bucket'. Allowed values: ${ALLOWED_BUCKETS.joinToString()}"
            )
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val fromTime = from?.let { parseTimestamp(it) } ?: now.minusDays(7)
        val toTime   = to?.let   { parseTimestamp(it) } ?: now

        if (fromTime.isAfter(toTime)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'")
        }

        val maxLimit = if (bucket != null) MAX_BUCKETED_LIMIT else MAX_RAW_LIMIT
        val params = PriceHistoryParams(
            skinId   = skinId,
            wearId   = wearId,
            source   = source,
            currency = currency,
            from     = fromTime,
            to       = toTime,
            limit    = limit.coerceIn(1, maxLimit),
            offset   = offset.coerceAtLeast(0)
        )

        return if (bucket != null) {
            val history: List<BucketedPriceResponse> = priceService.getBucketedPriceHistory(params, bucket)
            ResponseEntity.ok(history)
        } else {
            val history: List<RawPriceHistoryResponse> = priceService.getRawPriceHistory(params)
            ResponseEntity.ok(history)
        }
    }

    private fun parseTimestamp(ts: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(ts)
        } catch (e: DateTimeParseException) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid timestamp '$ts'. Expected ISO 8601 / RFC 3339 format."
            )
        }

    companion object {
        private val ALLOWED_BUCKETS = setOf("1h", "6h", "1d", "7d", "30d")
        private const val MAX_RAW_LIMIT      = 1000
        private const val MAX_BUCKETED_LIMIT = 500
    }
}

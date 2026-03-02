package com.nogaemer.cs2skins.dto

import java.math.BigDecimal
import java.time.OffsetDateTime

// Response DTOs for API
data class SkinResponse(
    val skinId: String,
    val name: String,
    val collectionId: String?,
    val collectionName: String?,
    val weaponId: String?,
    val weaponName: String?,
    val rarityId: String?,
    val rarityName: String?,
    val rarityColor: String?,
    val stattrak: Boolean,
    val minFloat: Double,
    val maxFloat: Double,
    val image: String?,
    val prices: Map<String, PriceInfo>?
)

data class PriceInfo(
    val price: BigDecimal,
    val quantity: Int
)

data class CollectionResponse(
    val collectionId: String,
    val name: String,
    val image: String?,
    val skinCount: Int? = null
)

data class CollectionWithSkinsResponse(
    val collectionId: String,
    val name: String,
    val image: String?,
    val skins: List<SkinResponse>
)

data class TradeUpResultResponse(
    val id: Int,
    val collectionA: CollectionInfo,
    val collectionB: CollectionInfo,
    val rarity: RarityInfo?,
    val stattrak: Boolean,
    val outputFloat: Double,
    val roi: Double,
    val profit: Double,
    val inputCost: Double,
    val outputCost: Double,
    val inputCostNoDropChange: Double?,
    val profitNoDropChange: Double?,
    val roiNoDropChange: Double?,
    val profitChance: Double?,
    val profitChanceNoDropChange: Double?,
    val inputs: List<TradeUpInputInfo>,
    val outputs: List<TradeUpOutputInfo>,
    val createdAt: Long
)

data class CollectionInfo(
    val collectionId: String,
    val name: String
)

data class RarityInfo(
    val rarityId: String,
    val name: String,
    val colorHex: String?
)

data class TradeUpInputInfo(
    val skinId: String,
    val skinName: String,
    val amount: Int,
    val floatValue: Double,
    val pricePerUnit: BigDecimal
)

data class TradeUpOutputInfo(
    val skinId: String,
    val skinName: String,
    val probability: Double,
    val floatValue: Double
)

data class JobStatusResponse(
    val status: String,
    val message: String
)

// Filter/Query DTOs
data class SkinFilterRequest(
    val weaponId: String? = null,
    val rarityId: String? = null,
    val collectionId: String? = null,
    val stattrak: Boolean? = null,
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val searchTerm: String? = null
)

data class TradeUpFilterRequest(
    val minRoi: Double? = null,
    val maxRoi: Double? = null,
    val minProfit: Double? = null,
    val maxProfit: Double? = null,
    val stattrak: Boolean? = null,
    val rarityId: String? = null,
    val sortBy: String = "roi", // roi, profit, inputCost, updatedAt
    val sortDirection: String = "desc", // asc, desc
    val page: Int = 0, // Page number (0-indexed)
    val size: Int = 20 // Page size
)

// Pagination response wrapper
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val isFirst: Boolean,
    val isLast: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

/** A single time-bucketed point in a trade-up history series. */
data class TradeUpHistoryPoint(
    val bucketStart: Long,
    val roi: Double,
    val profit: Double,
    val inputCost: Double,
    val outputCost: Double,
    val inputCostNoDropChange: Double? = null,
    val profitNoDropChange: Double? = null,
    val roiNoDropChange: Double? = null,
    /** Fraction of outcomes where output value ≥ input cost (with drop-change adjustment). */
    val profitChance: Double? = null,
    /** Fraction of outcomes where output value ≥ input cost (no drop-change adjustment). */
    val profitChanceNoDropChange: Double? = null,
    /** Number of raw snapshots aggregated into this bucket. */
    val samples: Int = 1
)

/**
 * Aggregated risk summary for a trade-up over a given time window.
 * Risk columns ([probProfit], [variance], [p05], [p50], [p95]) are null
 * when no snapshot in the window carries risk-metric data yet.
 */
data class TradeUpRiskResponse(
    val tradeupId: Int,
    val from: Long,
    val to: Long,
    val avgRoi: Double,
    val samples: Long,
    /** Fraction of outcome values with profit > 0 (0.0 – 1.0). */
    val probProfit: Double?,
    /** Weighted variance of the output-value distribution. */
    val variance: Double?,
    /** 5th percentile of the output-value distribution. */
    val p05: Double?,
    /** 50th percentile (median) of the output-value distribution. */
    val p50: Double?,
    /** 95th percentile of the output-value distribution. */
    val p95: Double?
)

/** A single entry in the ranked top-tradeups response. */
data class TopTradeupEntry(
    val tradeupId: Int,
    val avgRoi: Double,
    val avgProfit: Double,
    val samples: Long,
    val stattrak: Boolean,
    val rarityId: String?,
    val rarityName: String?
)

/**
 * Response for GET /api/tradeups/top.
 * [source] is "aggregate" when the data comes from the `tradeup_daily`
 * materialized view; "raw" when it falls back to raw snapshots.
 */
data class TopTradeupResponse(
    val tradeups: List<TopTradeupEntry>,
    val from: Long,
    val to: Long,
    val bucket: String,
    /** "aggregate" when tradeup_daily was used; "raw" otherwise. */
    val source: String
)

/** A single time-bucketed point in a skin price history series. */
data class SkinPriceHistoryResponse(
    val skinId: String,
    val wearId: String,
    val sourceId: Int,
    val sourceName: String,
    val currencyId: Int,
    val currencyCode: String,
    val recordedAt: Long,
    val price: java.math.BigDecimal,
    val quantity: Int
)

/** Response item for GET /api/prices/skins/{skinId}/latest */
data class LatestPriceResponse(
    val skinId: String,
    val wearId: String,
    val sourceId: Int,
    val sourceName: String,
    val currencyId: Int,
    val currencyCode: String,
    val price: BigDecimal,
    val quantity: Int,
    val updatedAt: OffsetDateTime
)

/** Response item for GET /api/prices/skins/{skinId}/history (raw time-series) */
data class RawPriceHistoryResponse(
    val skinId: String,
    val wearId: String,
    val sourceId: Int,
    val sourceName: String,
    val currencyId: Int,
    val currencyCode: String,
    val price: BigDecimal,
    val quantity: Int,
    val recordedAt: OffsetDateTime
)

/** Response item for GET /api/prices/skins/{skinId}/history (bucketed via time_bucket) */
data class BucketedPriceResponse(
    val bucket: OffsetDateTime,
    val wearId: String,
    val sourceId: Int,
    val sourceName: String,
    val currencyId: Int,
    val currencyCode: String,
    val avgPrice: BigDecimal,
    val minPrice: BigDecimal,
    val maxPrice: BigDecimal
)

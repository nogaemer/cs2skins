package com.nogaemer.cs2skins.dto

import java.math.BigDecimal

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
    val floatValue: Double,
    val price: BigDecimal
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
    val sortBy: String = "roi", // roi, profit, inputCost, createdAt
    val sortDirection: String = "desc" // asc, desc
)

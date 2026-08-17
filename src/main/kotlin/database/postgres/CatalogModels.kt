package database.postgres

import java.math.BigDecimal
import java.time.OffsetDateTime

data class Collection(
    val id: Long,
    val externalId: String,
    val name: String,
    val imageUrl: String?
)

// Weapon no longer carries a serial id -- externalId IS the primary key now.
data class Weapon(
    val externalId: String,
    val name: String,
    val imageUrl: String?
)

data class Rarity(
    val id: Short,
    val externalId: String,
    val name: String,
    val colorHex: String?,
    val sortOrder: Short
)

data class Item(
    val id: Long,
    val externalId: String,
    val marketHashName: String,
    val name: String,
    val weaponId: String?, // matches weapons.external_id TEXT
    val collectionId: Long?,
    val rarityId: Short?,
    val patternId: String?,
    val patternName: String?,
    val minFloat: Double,
    val maxFloat: Double,
    val stattrak: Boolean,
    val souvenir: Boolean,
    val imageUrl: String?
)

data class WearBucket(
    val id: Short,
    val code: String,
    val displayName: String,
    val minFloat: Double,
    val maxFloat: Double,
    val generationMinFloat: Double,
    val probability: Double
)

/**
 * Phase "rating" addition: spread/slippage/price-impact/volatility, sourced
 * from openskin.dev's per-item-per-wear microstructure metrics. All nullable
 * because openskin can return partial data (e.g. price_impact is omitted
 * entirely when there isn't enough order-book depth to fill that quantity --
 * that's a meaningful signal in itself, not missing data, and is treated as
 * such by RatingCalculator).
 */
data class CurrentPrice(
    val itemId: Long,
    val wearBucketId: Short,
    val priceSourceId: Short,
    val observedAt: OffsetDateTime,
    val averagePrice: BigDecimal,
    val volume24h: Int,
    val buyPrice: BigDecimal? = null,
    val sellPrice: BigDecimal? = null,
    val liquidityScore: Double? = null,
    val spreadPct: Double? = null,
    val slippagePct: Double? = null,
    val priceImpact5Pct: Double? = null,
    val priceImpact10Pct: Double? = null,
    val volatility1d: Double? = null,
    val volatility7d: Double? = null
)
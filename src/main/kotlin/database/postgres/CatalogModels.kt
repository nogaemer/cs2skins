package database.postgres

import java.math.BigDecimal
import java.time.OffsetDateTime

data class Collection(
    val id: Long,
    val externalId: String,
    val name: String,
    val imageUrl: String?
)

// Weapon no longer carries a serial `id` -- external_id IS the primary key now.
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
    val weaponId: String?,   // was Short? -- now matches weapons.external_id (TEXT)
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

data class CurrentPrice(
    val itemId: Long,
    val wearBucketId: Short,
    val priceSourceId: Short,
    val observedAt: OffsetDateTime,
    val averagePrice: BigDecimal,
    val volume24h: Int,
    val buyPrice: BigDecimal? = null,
    val sellPrice: BigDecimal? = null,
    val liquidityScore: Double? = null
)

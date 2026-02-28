package database

import models.CSWear
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

object Collections : Table("collections") {
    val collectionId = varchar("collection_id", 255)
    val name = varchar("name", 255)
    val image = text("image").nullable()

    override val primaryKey = PrimaryKey(collectionId)
}

object Weapons : Table("weapons") {
    val weaponId = varchar("weapon_id", 255)
    val name = varchar("name", 255)
    val image = text("image").nullable()

    override val primaryKey = PrimaryKey(weaponId)
}

object Rarities : Table("rarities") {
    val rarityId = varchar("rarity_id", 255)
    val name = varchar("name", 100)
    val colorHex = char("color_hex", 6).nullable()

    override val primaryKey = PrimaryKey(rarityId)
}

object WearConditions : Table("wear_conditions") {
    val wearId = varchar("wear_id", 255)
    val name = varchar("name", 100)

    override val primaryKey = PrimaryKey(wearId)
}

/**
 * Price data providers (e.g. Steam, CSFloat, Buff163).
 */
object PriceSources : Table("price_sources") {
    val id = integer("id").autoIncrement()
    val name = text("name").uniqueIndex()
    val baseUrl = text("base_url").nullable()
    val active = bool("active").default(true)

    override val primaryKey = PrimaryKey(id)
}

/**
 * ISO 4217 currencies supported for pricing (e.g. USD, EUR, CNY).
 */
object Currencies : Table("currencies") {
    val id = integer("id").autoIncrement()
    val code = char("code", 3).uniqueIndex()
    val symbol = text("symbol").nullable()
    val isBase = bool("is_base").default(false)

    override val primaryKey = PrimaryKey(id)
}

object Skins : Table("skins") {
    val skinId = varchar("skin_id", 255)
    val collectionId = varchar("collection_id", 255)
        .references(Collections.collectionId, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val name = varchar("name", 255)
    val weaponId = varchar("weapon_id", 255)
        .references(Weapons.weaponId, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val patternId = varchar("pattern_id", 100).nullable()
    val patternName = varchar("pattern_name", 255).nullable()
    val minFloat = double("min_float")
    val maxFloat = double("max_float")
    val rarityId = varchar("rarity_id", 255)
        .references(Rarities.rarityId, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val stattrak = bool("stattrak").default(false)
    val image = text("image").nullable()

    override val primaryKey = PrimaryKey(skinId)

    init {
        index("idx_skins_collection", false, collectionId)
        index("idx_skins_weapon", false, weaponId)
        index("idx_skins_rarity", false, rarityId)
    }
}

/**
 * Current price snapshot per skin+wear+source+currency (latest known price).
 * This is the primary table for read queries that need the most recent price.
 */
object SkinPricesCurrent : Table("skin_prices_current") {
    val skinId = varchar("skin_id", 255)
        .references(Skins.skinId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val wearId = varchar("wear_id", 255)
        .references(WearConditions.wearId, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
    val sourceId = integer("source_id")
        .references(PriceSources.id, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
    val currencyId = integer("currency_id")
        .references(Currencies.id, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
    val price = decimal("price", 10, 2).default(java.math.BigDecimal.ZERO)
    val quantity = integer("quantity").default(0)
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(skinId, wearId, sourceId, currencyId)

    init {
        index("idx_spc_wear", false, wearId)
        index("idx_spc_source", false, sourceId)
        index("idx_spc_currency", false, currencyId)
    }
}

/**
 * Historical price records per skin+wear+source+currency (TimescaleDB hypertable on recorded_at).
 * Partitioned by week; stores one row per price snapshot event.
 * The recorded_at column holds a timestamptz value.
 * A surrogate `seq` auto-increment is included in the primary key to guarantee
 * uniqueness even when multiple snapshots are written within the same instant.
 */
object SkinPriceHistory : Table("skin_price_history") {
    val seq = integer("seq").autoIncrement()
    val skinId = varchar("skin_id", 255)
        .references(Skins.skinId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val wearId = varchar("wear_id", 255)
        .references(WearConditions.wearId, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
    val sourceId = integer("source_id")
        .references(PriceSources.id, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
    val currencyId = integer("currency_id")
        .references(Currencies.id, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
    val recordedAt = timestampWithTimeZone("recorded_at")
    val price = decimal("price", 10, 2).default(java.math.BigDecimal.ZERO)
    val quantity = integer("quantity").default(0)

    // seq makes the key unique; recordedAt must be included to satisfy TimescaleDB's
    // requirement that all UNIQUE/PK constraints include the partition column.
    override val primaryKey = PrimaryKey(seq, recordedAt)

    init {
        index("idx_sph_skin_wear", false, skinId, wearId)
        index("idx_sph_source", false, sourceId)
        index("idx_sph_currency", false, currencyId)
    }
}

object OutputPools : Table("output_pools") {
    val id = integer("id").autoIncrement()
    val hash = varchar("hash", 255).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}

object OutputPoolItems : Table("output_pool_items") {
    val id = integer("id").autoIncrement()
    val poolId = integer("pool_id").references(
        OutputPools.id,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    )
    val skinId = varchar("skin_id", 255)
        .references(Skins.skinId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val probability = double("probability")
    val floatValue = double("float_value")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_output_pool_items_pool", false, poolId)
    }
}

/**
 * Static trade-up definition (master record).
 * Represents a unique trade-up configuration: two collections, a rarity tier,
 * whether it is StatTrak, the specific input skins and split (amountA/amountB),
 * and the target output float.
 * Metrics (ROI, profit, etc.) live in TradeupsCurrent and TradeupSnapshots.
 */
object TradeupsMaster : Table("tradeups_master") {
    val id = integer("id").autoIncrement()
    val collectionAId = varchar("collection_a_id", 255)
        .references(Collections.collectionId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val collectionBId = varchar("collection_b_id", 255)
        .references(Collections.collectionId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val rarityId = varchar("rarity_id", 255)
        .references(Rarities.rarityId, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val stattrak = bool("stattrak").default(false)
    val skinAId = varchar("skin_a_id", 255)
        .references(Skins.skinId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val skinBId = varchar("skin_b_id", 255)
        .references(Skins.skinId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val amountA = integer("amount_a").nullable()
    val amountB = integer("amount_b").nullable()
    val outputFloat = double("output_float")
    val outputPoolId = integer("output_pool_id")
        .references(OutputPools.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_tm_stattrak", false, stattrak)
        index("idx_tm_rarity", false, rarityId)
        index("idx_tm_collections", false, collectionAId, collectionBId)
        index("idx_tm_skins", false, skinAId, skinBId)
        index("idx_tm_output_pool", false, outputPoolId)
        uniqueIndex(
            "uniq_tm_identity",
            collectionAId, collectionBId, rarityId, stattrak,
            skinAId, skinBId, amountA, amountB, outputFloat
        )
    }
}

/**
 * Latest computed metrics for each trade-up master record.
 * Updated (UPSERT) whenever a new snapshot is calculated.
 * Use this table for fast filtering and sorting of trade-ups.
 */
object TradeupsCurrent : Table("tradeups_current") {
    val tradeupId = integer("tradeup_id")
        .references(TradeupsMaster.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val roi = double("roi")
    val profit = double("profit")
    val inputCost = double("input_cost")
    val outputCost = double("output_cost")
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(tradeupId)

    init {
        index("idx_tc_roi", false, roi)
        index("idx_tc_profit", false, profit)
        index("idx_tc_roi_profit", false, roi, profit)
    }
}

/**
 * Time-series snapshots of trade-up metrics (TimescaleDB hypertable on snapshot_time).
 * One row per calculation event. Partitioned by week (chunk_time_interval = 7 days).
 * A surrogate `snapshotSeq` auto-increment is included in the primary key to guarantee
 * uniqueness even when multiple snapshots are written within the same instant.
 */
object TradeupSnapshots : Table("tradeup_snapshots") {
    val snapshotSeq = integer("snapshot_seq").autoIncrement()
    val tradeupId = integer("tradeup_id")
        .references(TradeupsMaster.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val snapshotTime = timestampWithTimeZone("snapshot_time")
    val roi = double("roi")
    val profit = double("profit")
    val inputCost = double("input_cost")
    val outputCost = double("output_cost")
    val probProfit = double("prob_profit").nullable()
    val variance = double("variance").nullable()
    val p05 = double("p05").nullable()
    val p50 = double("p50").nullable()
    val p95 = double("p95").nullable()

    // snapshotSeq makes the key unique; snapshotTime must be included to satisfy TimescaleDB's
    // requirement that all UNIQUE/PK constraints include the partition column.
    override val primaryKey = PrimaryKey(tradeupId, snapshotTime, snapshotSeq)

    init {
        // Index optimized for time-range queries on the hypertable
        index("idx_ts_snapshot_time", false, snapshotTime)
    }
}

object TradeUpInputs : Table("tradeup_inputs") {
    val id = integer("id").autoIncrement()
    val tradeUpResultId = integer("tradeup_result_id")
        .references(TradeupsMaster.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val skinId = varchar("skin_id", 255)
        .references(Skins.skinId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val skinName = varchar("skin_name", 255)
    val amount = integer("amount")
    val floatValue = double("float_value")
    val pricePerUnit = decimal("price_per_unit", 10, 2)

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_tradeup_input_result", false, tradeUpResultId)
    }
}


data class Collection(
    val collectionId: String,
    val name: String,
    val image: String?
)

data class Weapon(
    val weaponId: String,
    val name: String,
    val image: String?
)

data class Rarity(
    val rarityId: String,
    val name: String,
    val colorHex: String?
)

data class WearCondition(
    val wearId: String,
    val name: String
)

data class PriceSource(
    val id: Int,
    val name: String,
    val baseUrl: String?,
    val active: Boolean
)

data class Currency(
    val id: Int,
    val code: String,
    val symbol: String?,
    val isBase: Boolean
)

data class SkinDTO(
    val skinId: String,
    val collectionId: String?,
    val name: String,
    val weapon: Weapon,
    val price: MutableMap<CSWear, SkinPrice> = mutableMapOf(),
    val patternId: String?,
    val patternName: String?,
    val minFloat: Double,
    val maxFloat: Double,
    val rarity: Rarity,
    val stattrak: Boolean,
    val image: String?
)

data class SkinPrice(
    val skinId: String,
    val wear: WearCondition,
    val sourceId: Int,
    val currencyId: Int,
    val price: BigDecimal,
    val quantity: Int
)

/** Full current-price DTO including source and currency metadata (for API / new query methods). */
data class SkinPriceDTO(
    val skinId: String,
    val wearId: String,
    val sourceId: Int,
    val sourceName: String,
    val currencyId: Int,
    val currencyCode: String,
    val price: BigDecimal,
    val quantity: Int,
    val updatedAt: Long
)

/** Full history-price DTO including source and currency metadata. */
data class SkinPriceHistoryDTO(
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

// Skin with full details
data class SkinWithDetails(
    val skinDTO: SkinDTO,
    val collection: Collection?,
    val weapon: Weapon?,
    val rarity: Rarity?
)

// Price with wear condition name
data class PriceWithWear(
    val skinPrice: SkinPrice,
    val wearConditionName: String
)

/**
 * Parameters for querying price history (raw or bucketed).
 * [from] and [to] default to the last 7 days when not specified by the caller.
 */
data class PriceHistoryParams(
    val skinId: String,
    val wearId: String? = null,
    val source: String? = null,
    val currency: String? = null,
    val from: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7),
    val to: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    val limit: Int = 100,
    val offset: Int = 0
)


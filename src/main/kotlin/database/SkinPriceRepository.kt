package database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class SkinPriceRepository : SkinPriceRepositoryInterface {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    private fun ensureWearExists(wear: WearCondition): String {
        val exists = WearConditions.selectAll().where { WearConditions.wearId eq wear.wearId }.limit(1).any()
        if (!exists) {
            WearConditions.insert {
                it[WearConditions.wearId] = wear.wearId
                it[WearConditions.name] = wear.name
            }
        }
        return wear.wearId
    }

    /**
     * Upserts the current price atomically (PostgreSQL ON CONFLICT DO UPDATE)
     * and appends a history snapshot in the same transaction.
     */
    override suspend fun create(skinPrice: SkinPrice): SkinPrice = dbQuery {
        val wearId = ensureWearExists(skinPrice.wear)
        val now = System.currentTimeMillis()

        // Atomic upsert: INSERT … ON CONFLICT (skin_id, wear_id) DO UPDATE SET …
        SkinPricesCurrent.upsert {
            it[SkinPricesCurrent.skinId] = skinPrice.skinId
            it[SkinPricesCurrent.wearId] = wearId
            it[SkinPricesCurrent.price] = skinPrice.price
            it[SkinPricesCurrent.quantity] = skinPrice.quantity
            it[SkinPricesCurrent.updatedAt] = now
        }

        // Always append a history snapshot
        SkinPriceHistory.insert {
            it[SkinPriceHistory.skinId] = skinPrice.skinId
            it[SkinPriceHistory.wearId] = wearId
            it[SkinPriceHistory.recordedAt] = now
            it[SkinPriceHistory.price] = skinPrice.price
            it[SkinPriceHistory.quantity] = skinPrice.quantity
        }

        skinPrice
    }

    override suspend fun findBySkin(skinId: String): List<SkinPrice> = dbQuery {
        val rows = SkinPricesCurrent.selectAll().where { SkinPricesCurrent.skinId eq skinId }.toList()
        if (rows.isEmpty()) return@dbQuery emptyList()
        val wearById = loadWearConditions(rows.map { it[SkinPricesCurrent.wearId] }.distinct())
        rows.map { rowToSkinPrice(it, wearById) }
    }

    override suspend fun findBySkinAndWear(skinId: String, wearId: String): SkinPrice? = dbQuery {
        val row = SkinPricesCurrent.selectAll()
            .where { (SkinPricesCurrent.skinId eq skinId) and (SkinPricesCurrent.wearId eq wearId) }
            .singleOrNull() ?: return@dbQuery null
        val wearById = loadWearConditions(listOf(wearId))
        rowToSkinPrice(row, wearById)
    }

    override suspend fun findWithWearCondition(skinId: String): List<PriceWithWear> = dbQuery {
        SkinPricesCurrent.join(
            WearConditions, JoinType.INNER,
            additionalConstraint = { SkinPricesCurrent.wearId eq WearConditions.wearId })
            .selectAll()
            .where { SkinPricesCurrent.skinId eq skinId }
            .map { row ->
                val wearCondition = WearCondition(row[WearConditions.wearId], row[WearConditions.name])
                PriceWithWear(
                    skinPrice = rowToSkinPrice(row, wearCondition),
                    wearConditionName = row[WearConditions.name]
                )
            }
    }

    /**
     * Returns the price history for a skin+wear combination, optionally filtered by time range.
     * Results are ordered by recordedAt ascending.
     *
     * @param skinId   the skin identifier
     * @param wearId   the wear condition identifier
     * @param fromMs   start of time range (epoch milliseconds, inclusive), or null for all history
     * @param toMs     end of time range (epoch milliseconds, inclusive), or null for all history
     */
    override suspend fun findHistory(
        skinId: String,
        wearId: String,
        fromMs: Long?,
        toMs: Long?
    ): List<SkinPriceHistoryPoint> = dbQuery {
        var query = SkinPriceHistory.selectAll()
            .where { (SkinPriceHistory.skinId eq skinId) and (SkinPriceHistory.wearId eq wearId) }

        fromMs?.let { query = query.andWhere { SkinPriceHistory.recordedAt greaterEq it } }
        toMs?.let { query = query.andWhere { SkinPriceHistory.recordedAt lessEq it } }

        query.orderBy(SkinPriceHistory.recordedAt to SortOrder.ASC).map { row ->
            SkinPriceHistoryPoint(
                skinId = row[SkinPriceHistory.skinId],
                wearId = row[SkinPriceHistory.wearId],
                recordedAt = row[SkinPriceHistory.recordedAt],
                price = row[SkinPriceHistory.price],
                quantity = row[SkinPriceHistory.quantity]
            )
        }
    }

    override suspend fun update(skinPrice: SkinPrice): Boolean = dbQuery {
        val wearId = ensureWearExists(skinPrice.wear)
        val now = System.currentTimeMillis()

        val updated = SkinPricesCurrent.update({
            (SkinPricesCurrent.skinId eq skinPrice.skinId) and (SkinPricesCurrent.wearId eq wearId)
        }) {
            it[SkinPricesCurrent.price] = skinPrice.price
            it[SkinPricesCurrent.quantity] = skinPrice.quantity
            it[SkinPricesCurrent.updatedAt] = now
        } > 0

        if (updated) {
            SkinPriceHistory.insert {
                it[SkinPriceHistory.skinId] = skinPrice.skinId
                it[SkinPriceHistory.wearId] = wearId
                it[SkinPriceHistory.recordedAt] = now
                it[SkinPriceHistory.price] = skinPrice.price
                it[SkinPriceHistory.quantity] = skinPrice.quantity
            }
        }

        updated
    }

    override suspend fun deleteAll(): Boolean = dbQuery {
        val historyCleaned = SkinPriceHistory.deleteAll() > 0
        val currentCleaned = SkinPricesCurrent.deleteAll() > 0
        historyCleaned || currentCleaned
    }

    /** Batch-loads wear conditions for the given ids to avoid N+1 queries. */
    private fun loadWearConditions(wearIds: List<String>): Map<String, WearCondition> {
        if (wearIds.isEmpty()) return emptyMap()
        return WearConditions.selectAll()
            .where { WearConditions.wearId inList wearIds }
            .associate { it[WearConditions.wearId] to WearCondition(it[WearConditions.wearId], it[WearConditions.name]) }
    }

    /** Used when the WearCondition is already known (e.g. from a JOIN). */
    private fun rowToSkinPrice(row: ResultRow, wear: WearCondition) = SkinPrice(
        skinId = row[SkinPricesCurrent.skinId],
        wear = wear,
        price = row[SkinPricesCurrent.price],
        quantity = row[SkinPricesCurrent.quantity]
    )

    /** Used when wear conditions have been pre-loaded in bulk. */
    private fun rowToSkinPrice(row: ResultRow, wearById: Map<String, WearCondition>) =
        rowToSkinPrice(row, wearById[row[SkinPricesCurrent.wearId]] ?: WearCondition(row[SkinPricesCurrent.wearId], ""))
}

/** Immutable point in a skin price history series. */
data class SkinPriceHistoryPoint(
    val skinId: String,
    val wearId: String,
    val recordedAt: Long,
    val price: java.math.BigDecimal,
    val quantity: Int
)

interface SkinPriceRepositoryInterface {
    suspend fun create(skinPrice: SkinPrice): SkinPrice
    suspend fun findBySkin(skinId: String): List<SkinPrice>
    suspend fun findBySkinAndWear(skinId: String, wearId: String): SkinPrice?
    suspend fun findWithWearCondition(skinId: String): List<PriceWithWear>
    suspend fun findHistory(skinId: String, wearId: String, fromMs: Long? = null, toMs: Long? = null): List<SkinPriceHistoryPoint>
    suspend fun update(skinPrice: SkinPrice): Boolean
    suspend fun deleteAll(): Boolean
}



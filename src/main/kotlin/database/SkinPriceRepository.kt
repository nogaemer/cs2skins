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
     * Upserts the current price and inserts a history snapshot in a single transaction.
     */
    override suspend fun create(skinPrice: SkinPrice): SkinPrice = dbQuery {
        val wearId = ensureWearExists(skinPrice.wear)
        val now = System.currentTimeMillis()

        // Upsert into skin_prices_current
        val existing = SkinPricesCurrent.selectAll()
            .where { (SkinPricesCurrent.skinId eq skinPrice.skinId) and (SkinPricesCurrent.wearId eq wearId) }
            .singleOrNull()

        if (existing == null) {
            SkinPricesCurrent.insert {
                it[SkinPricesCurrent.skinId] = skinPrice.skinId
                it[SkinPricesCurrent.wearId] = wearId
                it[SkinPricesCurrent.price] = skinPrice.price
                it[SkinPricesCurrent.quantity] = skinPrice.quantity
                it[SkinPricesCurrent.updatedAt] = now
            }
        } else {
            SkinPricesCurrent.update({
                (SkinPricesCurrent.skinId eq skinPrice.skinId) and (SkinPricesCurrent.wearId eq wearId)
            }) {
                it[SkinPricesCurrent.price] = skinPrice.price
                it[SkinPricesCurrent.quantity] = skinPrice.quantity
                it[SkinPricesCurrent.updatedAt] = now
            }
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
        SkinPricesCurrent.selectAll().where { SkinPricesCurrent.skinId eq skinId }
            .map { rowToSkinPrice(it) }
    }

    override suspend fun findBySkinAndWear(skinId: String, wearId: String): SkinPrice? = dbQuery {
        SkinPricesCurrent.selectAll()
            .where { (SkinPricesCurrent.skinId eq skinId) and (SkinPricesCurrent.wearId eq wearId) }
            .map { rowToSkinPrice(it) }
            .singleOrNull()
    }

    override suspend fun findWithWearCondition(skinId: String): List<PriceWithWear> = dbQuery {
        SkinPricesCurrent.join(
            WearConditions, JoinType.INNER,
            additionalConstraint = { SkinPricesCurrent.wearId eq WearConditions.wearId })
            .selectAll()
            .where { SkinPricesCurrent.skinId eq skinId }
            .map { row ->
                PriceWithWear(
                    skinPrice = rowToSkinPrice(row),
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

    private fun rowToSkinPrice(row: ResultRow) = SkinPrice(
        skinId = row[SkinPricesCurrent.skinId],
        wear = run {
            val wearIdValue = row[SkinPricesCurrent.wearId]
            WearConditions.selectAll().where { WearConditions.wearId eq wearIdValue }
                .limit(1)
                .map { rw -> WearCondition(wearId = rw[WearConditions.wearId], name = rw[WearConditions.name]) }
                .single()
        },
        price = row[SkinPricesCurrent.price],
        quantity = row[SkinPricesCurrent.quantity]
    )
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


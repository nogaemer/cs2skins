package database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val nowMs = now.toInstant().toEpochMilli()

        // Atomic upsert: INSERT … ON CONFLICT (skin_id, wear_id, source_id, currency_id) DO UPDATE SET …
        SkinPricesCurrent.upsert {
            it[SkinPricesCurrent.skinId] = skinPrice.skinId
            it[SkinPricesCurrent.wearId] = wearId
            it[SkinPricesCurrent.sourceId] = skinPrice.sourceId
            it[SkinPricesCurrent.currencyId] = skinPrice.currencyId
            it[SkinPricesCurrent.price] = skinPrice.price
            it[SkinPricesCurrent.quantity] = skinPrice.quantity
            it[SkinPricesCurrent.updatedAt] = nowMs
        }

        // Always append a history snapshot
        SkinPriceHistory.insert {
            it[SkinPriceHistory.skinId] = skinPrice.skinId
            it[SkinPriceHistory.wearId] = wearId
            it[SkinPriceHistory.sourceId] = skinPrice.sourceId
            it[SkinPriceHistory.currencyId] = skinPrice.currencyId
            it[SkinPriceHistory.recordedAt] = now
            it[SkinPriceHistory.price] = skinPrice.price
            it[SkinPriceHistory.quantity] = skinPrice.quantity
        }

        skinPrice
    }

    override suspend fun createAll(skinPrices: List<SkinPrice>) = dbQuery {
        if (skinPrices.isEmpty()) return@dbQuery
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val nowMs = now.toInstant().toEpochMilli()

        // Batch-ensure all unique wear conditions exist in one go
        val uniqueWears = skinPrices.map { it.wear }.distinctBy { it.wearId }
        WearConditions.batchInsert(uniqueWears, ignore = true) { wear ->
            this[WearConditions.wearId] = wear.wearId
            this[WearConditions.name] = wear.name
        }

        // Bulk upsert current prices (INSERT … ON CONFLICT DO UPDATE)
        SkinPricesCurrent.batchUpsert(skinPrices) { skinPrice ->
            this[SkinPricesCurrent.skinId] = skinPrice.skinId
            this[SkinPricesCurrent.wearId] = skinPrice.wear.wearId
            this[SkinPricesCurrent.sourceId] = skinPrice.sourceId
            this[SkinPricesCurrent.currencyId] = skinPrice.currencyId
            this[SkinPricesCurrent.price] = skinPrice.price
            this[SkinPricesCurrent.quantity] = skinPrice.quantity
            this[SkinPricesCurrent.updatedAt] = nowMs
        }

        // Bulk insert history snapshots
        SkinPriceHistory.batchInsert(skinPrices) { skinPrice ->
            this[SkinPriceHistory.skinId] = skinPrice.skinId
            this[SkinPriceHistory.wearId] = skinPrice.wear.wearId
            this[SkinPriceHistory.sourceId] = skinPrice.sourceId
            this[SkinPriceHistory.currencyId] = skinPrice.currencyId
            this[SkinPriceHistory.recordedAt] = now
            this[SkinPriceHistory.price] = skinPrice.price
            this[SkinPriceHistory.quantity] = skinPrice.quantity
        }
    }


    override suspend fun findBySkin(
        skinId: String,
        sourceId: Int?,
        currencyId: Int?
    ): List<SkinPrice> = dbQuery {
        var query = SkinPricesCurrent.selectAll().where { SkinPricesCurrent.skinId eq skinId }
        sourceId?.let { query = query.andWhere { SkinPricesCurrent.sourceId eq it } }
        currencyId?.let { query = query.andWhere { SkinPricesCurrent.currencyId eq it } }
        val rows = query.toList()
        if (rows.isEmpty()) return@dbQuery emptyList()
        val wearById = loadWearConditions(rows.map { it[SkinPricesCurrent.wearId] }.distinct())
        rows.map { rowToSkinPrice(it, wearById) }
    }

    override suspend fun findBySkinAndWear(
        skinId: String,
        wearId: String,
        sourceId: Int?,
        currencyId: Int?
    ): SkinPrice? = dbQuery {
        var query = SkinPricesCurrent.selectAll()
            .where { (SkinPricesCurrent.skinId eq skinId) and (SkinPricesCurrent.wearId eq wearId) }
        sourceId?.let { query = query.andWhere { SkinPricesCurrent.sourceId eq it } }
        currencyId?.let { query = query.andWhere { SkinPricesCurrent.currencyId eq it } }
        // When both sourceId and currencyId are given the composite PK guarantees at most one row;
        // otherwise multiple rows may exist (one per source/currency) and we return the first.
        val row = if (sourceId != null && currencyId != null) query.singleOrNull() else query.firstOrNull()
        row ?: return@dbQuery null
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
     * Returns the current price for a specific skin+wear+source+currency combination.
     */
    override suspend fun getCurrentPrice(
        skinId: String,
        wearId: String,
        sourceId: Int,
        currencyId: Int
    ): SkinPriceDTO? = dbQuery {
        SkinPricesCurrent
            .join(PriceSources, JoinType.INNER, SkinPricesCurrent.sourceId, PriceSources.id)
            .join(Currencies, JoinType.INNER, SkinPricesCurrent.currencyId, Currencies.id)
            .selectAll()
            .where {
                (SkinPricesCurrent.skinId eq skinId) and
                (SkinPricesCurrent.wearId eq wearId) and
                (SkinPricesCurrent.sourceId eq sourceId) and
                (SkinPricesCurrent.currencyId eq currencyId)
            }
            .firstOrNull()
            ?.let { rowToSkinPriceDTO(it) }
    }

    /**
     * Returns the latest price for a skin+wear from all available sources in a given currency.
     */
    override suspend fun getLatestPriceAllSources(
        skinId: String,
        wearId: String,
        currencyId: Int
    ): List<SkinPriceDTO> = dbQuery {
        SkinPricesCurrent
            .join(PriceSources, JoinType.INNER, SkinPricesCurrent.sourceId, PriceSources.id)
            .join(Currencies, JoinType.INNER, SkinPricesCurrent.currencyId, Currencies.id)
            .selectAll()
            .where {
                (SkinPricesCurrent.skinId eq skinId) and
                (SkinPricesCurrent.wearId eq wearId) and
                (SkinPricesCurrent.currencyId eq currencyId)
            }
            .map { rowToSkinPriceDTO(it) }
    }

    /**
     * Returns all current prices for a skin+wear in a given currency (alias for getLatestPriceAllSources).
     */
    override suspend fun getPriceByCurrency(
        skinId: String,
        wearId: String,
        currencyId: Int
    ): List<SkinPriceDTO> = getLatestPriceAllSources(skinId, wearId, currencyId)

    /**
     * Returns all current prices for a skin+wear from a given source across all currencies.
     */
    override suspend fun getPriceBySource(
        skinId: String,
        wearId: String,
        sourceId: Int
    ): List<SkinPriceDTO> = dbQuery {
        SkinPricesCurrent
            .join(PriceSources, JoinType.INNER, SkinPricesCurrent.sourceId, PriceSources.id)
            .join(Currencies, JoinType.INNER, SkinPricesCurrent.currencyId, Currencies.id)
            .selectAll()
            .where {
                (SkinPricesCurrent.skinId eq skinId) and
                (SkinPricesCurrent.wearId eq wearId) and
                (SkinPricesCurrent.sourceId eq sourceId)
            }
            .map { rowToSkinPriceDTO(it) }
    }

    /**
     * Returns the price history for a skin+wear combination, optionally filtered by
     * source, currency, and time range. Results are ordered by recordedAt ascending.
     *
     * @param skinId     the skin identifier
     * @param wearId     the wear condition identifier
     * @param sourceId   filter to a specific price source, or null for all sources
     * @param currencyId filter to a specific currency, or null for all currencies
     * @param from       start of time range (inclusive), or null for all history
     * @param to         end of time range (inclusive), or null for all history
     */
    override suspend fun findHistory(
        skinId: String,
        wearId: String,
        sourceId: Int?,
        currencyId: Int?,
        from: OffsetDateTime?,
        to: OffsetDateTime?
    ): List<SkinPriceHistoryDTO> = dbQuery {
        var query = SkinPriceHistory
            .join(PriceSources, JoinType.INNER, SkinPriceHistory.sourceId, PriceSources.id)
            .join(Currencies, JoinType.INNER, SkinPriceHistory.currencyId, Currencies.id)
            .selectAll()
            .where { (SkinPriceHistory.skinId eq skinId) and (SkinPriceHistory.wearId eq wearId) }

        sourceId?.let { query = query.andWhere { SkinPriceHistory.sourceId eq it } }
        currencyId?.let { query = query.andWhere { SkinPriceHistory.currencyId eq it } }
        from?.let { query = query.andWhere { SkinPriceHistory.recordedAt greaterEq it } }
        to?.let { query = query.andWhere { SkinPriceHistory.recordedAt lessEq it } }

        query.orderBy(SkinPriceHistory.recordedAt to SortOrder.ASC).map { row ->
            rowToSkinPriceHistoryDTO(row)
        }
    }

    /**
     * Returns the full price history for a skin+wear+source+currency combination.
     */
    override suspend fun getPriceHistory(
        skinId: String,
        wearId: String,
        sourceId: Int,
        currencyId: Int,
        from: OffsetDateTime?,
        to: OffsetDateTime?
    ): List<SkinPriceHistoryDTO> = findHistory(skinId, wearId, sourceId, currencyId, from, to)

    override suspend fun update(skinPrice: SkinPrice): Boolean = dbQuery {
        val wearId = ensureWearExists(skinPrice.wear)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val nowMs = now.toInstant().toEpochMilli()

        val updated = SkinPricesCurrent.update({
            (SkinPricesCurrent.skinId eq skinPrice.skinId) and
            (SkinPricesCurrent.wearId eq wearId) and
            (SkinPricesCurrent.sourceId eq skinPrice.sourceId) and
            (SkinPricesCurrent.currencyId eq skinPrice.currencyId)
        }) {
            it[SkinPricesCurrent.price] = skinPrice.price
            it[SkinPricesCurrent.quantity] = skinPrice.quantity
            it[SkinPricesCurrent.updatedAt] = nowMs
        } > 0

        if (updated) {
            SkinPriceHistory.insert {
                it[SkinPriceHistory.skinId] = skinPrice.skinId
                it[SkinPriceHistory.wearId] = wearId
                it[SkinPriceHistory.sourceId] = skinPrice.sourceId
                it[SkinPriceHistory.currencyId] = skinPrice.currencyId
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
        sourceId = row[SkinPricesCurrent.sourceId],
        currencyId = row[SkinPricesCurrent.currencyId],
        price = row[SkinPricesCurrent.price],
        quantity = row[SkinPricesCurrent.quantity]
    )

    /** Used when wear conditions have been pre-loaded in bulk. */
    private fun rowToSkinPrice(row: ResultRow, wearById: Map<String, WearCondition>) =
        rowToSkinPrice(row, wearById[row[SkinPricesCurrent.wearId]] ?: WearCondition(row[SkinPricesCurrent.wearId], ""))

    /** Maps a joined row (SkinPricesCurrent + PriceSources + Currencies) to a SkinPriceDTO. */
    private fun rowToSkinPriceDTO(row: ResultRow) = SkinPriceDTO(
        skinId = row[SkinPricesCurrent.skinId],
        wearId = row[SkinPricesCurrent.wearId],
        sourceId = row[SkinPricesCurrent.sourceId],
        sourceName = row[PriceSources.name],
        currencyId = row[SkinPricesCurrent.currencyId],
        currencyCode = row[Currencies.code],
        price = row[SkinPricesCurrent.price],
        quantity = row[SkinPricesCurrent.quantity],
        updatedAt = row[SkinPricesCurrent.updatedAt]
    )

    /** Maps a joined row (SkinPriceHistory + PriceSources + Currencies) to a SkinPriceHistoryDTO. */
    private fun rowToSkinPriceHistoryDTO(row: ResultRow) = SkinPriceHistoryDTO(
        skinId = row[SkinPriceHistory.skinId],
        wearId = row[SkinPriceHistory.wearId],
        sourceId = row[SkinPriceHistory.sourceId],
        sourceName = row[PriceSources.name],
        currencyId = row[SkinPriceHistory.currencyId],
        currencyCode = row[Currencies.code],
        price = row[SkinPriceHistory.price],
        quantity = row[SkinPriceHistory.quantity],
        recordedAt = row[SkinPriceHistory.recordedAt]
    )
}

interface SkinPriceRepositoryInterface {
    suspend fun create(skinPrice: SkinPrice): SkinPrice
    suspend fun createAll(skinPrices: List<SkinPrice>)
    suspend fun findBySkin(skinId: String, sourceId: Int? = null, currencyId: Int? = null): List<SkinPrice>
    suspend fun findBySkinAndWear(skinId: String, wearId: String, sourceId: Int? = null, currencyId: Int? = null): SkinPrice?
    suspend fun findWithWearCondition(skinId: String): List<PriceWithWear>
    suspend fun getCurrentPrice(skinId: String, wearId: String, sourceId: Int, currencyId: Int): SkinPriceDTO?
    suspend fun getLatestPriceAllSources(skinId: String, wearId: String, currencyId: Int): List<SkinPriceDTO>
    suspend fun getPriceByCurrency(skinId: String, wearId: String, currencyId: Int): List<SkinPriceDTO>
    suspend fun getPriceBySource(skinId: String, wearId: String, sourceId: Int): List<SkinPriceDTO>
    suspend fun findHistory(
        skinId: String,
        wearId: String,
        sourceId: Int? = null,
        currencyId: Int? = null,
        from: OffsetDateTime? = null,
        to: OffsetDateTime? = null
    ): List<SkinPriceHistoryDTO>
    suspend fun getPriceHistory(
        skinId: String,
        wearId: String,
        sourceId: Int,
        currencyId: Int,
        from: OffsetDateTime? = null,
        to: OffsetDateTime? = null
    ): List<SkinPriceHistoryDTO>
    suspend fun update(skinPrice: SkinPrice): Boolean
    suspend fun deleteAll(): Boolean
}

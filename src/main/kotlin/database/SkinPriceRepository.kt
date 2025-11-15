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

    override suspend fun create(skinPrice: SkinPrice): SkinPrice = dbQuery {

        val insertedId = SkinPrices.insert {
            it[skinId] = skinPrice.skinId
            it[wearId] = ensureWearExists(skinPrice.wear)
            it[price] = skinPrice.price
            it[quantity] = skinPrice.quantity
        } get SkinPrices.id

        skinPrice.copy(id = insertedId)
    }

    override suspend fun findById(id: Int): SkinPrice? = dbQuery {
        SkinPrices.selectAll().where { SkinPrices.id eq id }
            .map { rowToSkinPrice(it) }
            .singleOrNull()
    }

    override suspend fun findBySkin(skinId: String): List<SkinPrice> = dbQuery {
        SkinPrices.selectAll().where { SkinPrices.skinId eq skinId }
            .map { rowToSkinPrice(it) }
    }

    override suspend fun findBySkinAndWear(skinId: String, wearId: String): SkinPrice? = dbQuery {
        SkinPrices.selectAll().where { (SkinPrices.skinId eq skinId) and (SkinPrices.wearId eq wearId) }
            .map { rowToSkinPrice(it) }
            .singleOrNull()
    }

    override suspend fun findWithWearCondition(skinId: String): List<PriceWithWear> = dbQuery {
        SkinPrices.join(
            WearConditions, JoinType.INNER,
            additionalConstraint = { SkinPrices.wearId eq WearConditions.wearId })
            .selectAll()
            .where { SkinPrices.skinId eq skinId }
            .map { row ->
                PriceWithWear(
                    skinPrice = rowToSkinPrice(row),
                    wearConditionName = row[WearConditions.name]
                )
            }
    }

    override suspend fun update(skinPrice: SkinPrice): Boolean = dbQuery {
        SkinPrices.update({ SkinPrices.id eq skinPrice.id }) {
            it[skinId] = skinPrice.skinId
            it[wearId] = ensureWearExists(skinPrice.wear)
            it[price] = skinPrice.price
            it[quantity] = skinPrice.quantity
        } > 0
    }

    override suspend fun delete(id: Int): Boolean = dbQuery {
        SkinPrices.deleteWhere { SkinPrices.id eq id } > 0
    }

    override suspend fun deleteAll(): Boolean = dbQuery {
        SkinPrices.deleteAll() > 0
    }

    private fun rowToSkinPrice(row: ResultRow) = SkinPrice(
        id = row[SkinPrices.id],
        skinId = row[SkinPrices.skinId],
        wear = run {
            val wearIdValue = row[SkinPrices.wearId]
            WearConditions.selectAll().where { WearConditions.wearId eq wearIdValue }
                .limit(1)
                .map { rw -> WearCondition(wearId = rw[WearConditions.wearId], name = rw[WearConditions.name]) }
                .single()
        },
        price = row[SkinPrices.price],
        quantity = row[SkinPrices.quantity]
    )
}


interface SkinPriceRepositoryInterface {
    suspend fun create(skinPrice: SkinPrice): SkinPrice
    suspend fun findById(id: Int): SkinPrice?
    suspend fun findBySkin(skinId: String): List<SkinPrice>
    suspend fun findBySkinAndWear(skinId: String, wearId: String): SkinPrice?
    suspend fun findWithWearCondition(skinId: String): List<PriceWithWear>
    suspend fun update(skinPrice: SkinPrice): Boolean
    suspend fun delete(id: Int): Boolean
    suspend fun deleteAll(): Boolean
}

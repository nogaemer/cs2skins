package database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class WearConditionRepository : WearConditionRepositoryInterface {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun create(wearCondition: WearCondition): WearCondition = dbQuery {
        WearConditions.insert {
            it[wearId] = wearCondition.wearId
            it[name] = wearCondition.name
        }
        wearCondition
    }

    override suspend fun findById(wearId: String): WearCondition? = dbQuery {
        WearConditions.selectAll().where { WearConditions.wearId eq wearId }
            .map { rowToWearCondition(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<WearCondition> = dbQuery {
        WearConditions.selectAll().map { rowToWearCondition(it) }
    }

    override suspend fun update(wearCondition: WearCondition): Boolean = dbQuery {
        WearConditions.update({ WearConditions.wearId eq wearCondition.wearId }) {
            it[name] = wearCondition.name
        } > 0
    }

    override suspend fun delete(wearId: String): Boolean = dbQuery {
        WearConditions.deleteWhere { WearConditions.wearId eq wearId } > 0
    }

    override suspend fun deleteAll(): Boolean = dbQuery {
        WearConditions.deleteAll() > 0
    }

    private fun rowToWearCondition(row: ResultRow) = WearCondition(
        wearId = row[WearConditions.wearId],
        name = row[WearConditions.name]
    )
}


interface WearConditionRepositoryInterface {
    suspend fun create(wearCondition: WearCondition): WearCondition
    suspend fun findById(wearId: String): WearCondition?
    suspend fun findAll(): List<WearCondition>
    suspend fun update(wearCondition: WearCondition): Boolean
    suspend fun delete(wearId: String): Boolean
    suspend fun deleteAll(): Boolean
}

package database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class CollectionRepository : CollectionRepositoryInterface {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun create(collection: Collection): Collection = dbQuery {
        Collections.insert {
            it[collectionId] = collection.collectionId
            it[name] = collection.name
            it[image] = collection.image
        }
        collection
    }

    override suspend fun findById(collectionId: String): Collection? = dbQuery {
        Collections.selectAll().where { Collections.collectionId eq collectionId }
            .map { rowToCollection(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<Collection> = dbQuery {
        Collections.selectAll().map { rowToCollection(it) }
    }

    override suspend fun update(collection: Collection): Boolean = dbQuery {
        Collections.update({ Collections.collectionId eq collection.collectionId }) {
            it[name] = collection.name
            it[image] = collection.image
        } > 0
    }

    override suspend fun delete(collectionId: String): Boolean = dbQuery {
        Collections.deleteWhere { Collections.collectionId eq collectionId } > 0
    }

    override suspend fun deleteAll(): Boolean = dbQuery {
        Collections.deleteAll() > 0
    }


    private fun rowToCollection(row: ResultRow) = Collection(
        collectionId = row[Collections.collectionId],
        name = row[Collections.name],
        image = row[Collections.image]
    )
}

interface CollectionRepositoryInterface {
    suspend fun create(collection: Collection): Collection
    suspend fun findById(collectionId: String): Collection?
    suspend fun findAll(): List<Collection>
    suspend fun update(collection: Collection): Boolean
    suspend fun delete(collectionId: String): Boolean
    suspend fun deleteAll(): Boolean
}

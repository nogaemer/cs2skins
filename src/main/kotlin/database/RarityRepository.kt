package database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Repository

@Repository
class RarityRepository : RarityRepositoryInterface {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    @CacheEvict(value = ["rarities"], allEntries = true)
    override suspend fun create(rarity: Rarity): Rarity = dbQuery {
        Rarities.insert {
            it[rarityId] = rarity.rarityId
            it[name] = rarity.name
            it[colorHex] = rarity.colorHex
        }
        rarity
    }

    @Cacheable(value = ["rarities"], key = "#rarityId")
    override suspend fun findById(rarityId: String): Rarity? = dbQuery {
        Rarities.selectAll().where { Rarities.rarityId eq rarityId }
            .map { rowToRarity(it) }
            .singleOrNull()
    }

    @Cacheable(value = ["rarities"], key = "'all'")
    override suspend fun findAll(): List<Rarity> = dbQuery {
        Rarities.selectAll().map { rowToRarity(it) }
    }

    @CacheEvict(value = ["rarities"], allEntries = true)
    override suspend fun update(rarity: Rarity): Boolean = dbQuery {
        Rarities.update({ Rarities.rarityId eq rarity.rarityId }) {
            it[name] = rarity.name
            it[colorHex] = rarity.colorHex
        } > 0
    }

    @CacheEvict(value = ["rarities"], allEntries = true)
    override suspend fun delete(rarityId: String): Boolean = dbQuery {
        Rarities.deleteWhere { Rarities.rarityId eq rarityId } > 0
    }

    @CacheEvict(value = ["rarities"], allEntries = true)
    override suspend fun deleteAll(): Boolean = dbQuery {
        Rarities.deleteAll() > 0
    }

    private fun rowToRarity(row: ResultRow) = Rarity(
        rarityId = row[Rarities.rarityId],
        name = row[Rarities.name],
        colorHex = row[Rarities.colorHex]
    )
}


interface RarityRepositoryInterface {
    suspend fun create(rarity: Rarity): Rarity
    suspend fun findById(rarityId: String): Rarity?
    suspend fun findAll(): List<Rarity>
    suspend fun update(rarity: Rarity): Boolean
    suspend fun delete(rarityId: String): Boolean
    suspend fun deleteAll(): Boolean
}

package database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class WeaponRepository : WeaponRepositoryInterface {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun create(weapon: Weapon): Weapon = dbQuery {
        Weapons.insert {
            it[weaponId] = weapon.weaponId
            it[name] = weapon.name
            it[image] = weapon.image
        }
        weapon
    }

    override suspend fun findById(weaponId: String): Weapon? = dbQuery {
        Weapons.selectAll().where { Weapons.weaponId eq weaponId }
            .map { rowToWeapon(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<Weapon> = dbQuery {
        Weapons.selectAll().map { rowToWeapon(it) }
    }

    override suspend fun update(weapon: Weapon): Boolean = dbQuery {
        Weapons.update({ Weapons.weaponId eq weapon.weaponId }) {
            it[name] = weapon.name
            it[image] = weapon.image
        } > 0
    }

    override suspend fun delete(weaponId: String): Boolean = dbQuery {
        Weapons.deleteWhere { Weapons.weaponId eq weaponId } > 0
    }

    override suspend fun deleteAll(): Boolean = dbQuery {
        Weapons.deleteAll() > 0
    }

    private fun rowToWeapon(row: ResultRow) = Weapon(
        weaponId = row[Weapons.weaponId],
        name = row[Weapons.name],
        image = row[Weapons.image]
    )
}


interface WeaponRepositoryInterface {
    suspend fun create(weapon: Weapon): Weapon
    suspend fun findById(weaponId: String): Weapon?
    suspend fun findAll(): List<Weapon>
    suspend fun update(weapon: Weapon): Boolean
    suspend fun delete(weaponId: String): Boolean
    suspend fun deleteAll(): Boolean
}

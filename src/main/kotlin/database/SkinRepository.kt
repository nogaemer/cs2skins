package database

import kotlinx.coroutines.Dispatchers
import models.CSWear
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class SkinRepository : SkinRepositoryInterface {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    private fun ensureRarityExists(rarity: Rarity): String {
        val exists = Rarities.selectAll().where { Rarities.rarityId eq rarity.rarityId }.limit(1).any()
        if (!exists) {
            Rarities.insert {
                it[Rarities.rarityId] = rarity.rarityId
                it[Rarities.name] = rarity.name
                it[Rarities.colorHex] = rarity.colorHex
            }
        }
        return rarity.rarityId
    }

    private fun ensureWeaponExists(weapon: Weapon): String {
        val exists = Weapons.selectAll().where { Weapons.weaponId eq weapon.weaponId }.limit(1).any()
        if (!exists) {
            Weapons.insert {
                it[Weapons.weaponId] = weapon.weaponId
                it[Weapons.name] = weapon.name
                it[Weapons.image] = weapon.image
            }
        }
        return weapon.weaponId
    }

    override suspend fun create(skinDTO: SkinDTO): SkinDTO = dbQuery {

        Skins.insert {
            it[skinId] = skinDTO.skinId
            it[collectionId] = skinDTO.collectionId
            it[name] = skinDTO.name
            it[weaponId] = ensureWeaponExists(skinDTO.weapon)
            it[patternId] = skinDTO.patternId
            it[patternName] = skinDTO.patternName
            it[minFloat] = skinDTO.minFloat
            it[maxFloat] = skinDTO.maxFloat
            it[rarityId] = ensureRarityExists(skinDTO.rarity)
            it[stattrak] = skinDTO.stattrak
            it[image] = skinDTO.image
        }
        skinDTO
    }

    override suspend fun findById(skinId: String): SkinDTO? = dbQuery {
        Skins.selectAll().where { Skins.skinId eq skinId }
            .map { rowToSkin(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<SkinDTO> = dbQuery {
        Skins.selectAll().map { rowToSkin(it) }
    }

    override suspend fun findByCollection(collectionId: String): List<SkinDTO> = dbQuery {
        Skins.selectAll().where { Skins.collectionId eq collectionId }
            .map { rowToSkin(it) }
    }

    override suspend fun findByCollectionWithPrice(collectionId: String, stattrak: Boolean): List<SkinDTO> = dbQuery {
        // fetch skins for collection
        val skins = Skins.selectAll().where { (Skins.collectionId eq collectionId) and (Skins.stattrak eq stattrak)}
            .map { rowToSkin(it) }

        if (skins.isEmpty()) return@dbQuery emptyList()

        // fetch all prices for these skins in a single query to avoid N+1
        val skinIds = skins.map { it.skinId }
        val pricesBySkin = SkinPrices.selectAll().where { SkinPrices.skinId inList skinIds }
            .mapNotNull { r ->
                val wear = CSWear.fromId(r[SkinPrices.wearId]) ?: return@mapNotNull null
                val skinPrice = SkinPrice(
                    r[SkinPrices.id],
                    r[SkinPrices.skinId],
                    WearCondition(r[SkinPrices.wearId], ""),
                    r[SkinPrices.price],
                    r[SkinPrices.quantity]
                )
                r[SkinPrices.skinId] to (wear to skinPrice)
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { entry ->
                entry.value.associate { it.first to it.second }.toMutableMap()
            }

        skins.map { skin ->
            val latest = pricesBySkin[skin.skinId]
            if (!latest.isNullOrEmpty()) skin.copy(price = latest) else skin
        }
    }

    suspend fun findWithFiltersAndPrices(
        weaponId: String? = null,
        rarityId: String? = null,
        collectionId: String? = null,
        stattrak: Boolean? = null,
        searchTerm: String? = null
    ): List<SkinDTO> = dbQuery {
        // Build query with filters
        var query = Skins.selectAll()
        
        weaponId?.let { query = query.andWhere { Skins.weaponId eq it } }
        rarityId?.let { query = query.andWhere { Skins.rarityId eq it } }
        collectionId?.let { query = query.andWhere { Skins.collectionId eq it } }
        stattrak?.let { query = query.andWhere { Skins.stattrak eq it } }
        searchTerm?.let { term ->
            query = query.andWhere { 
                (Skins.name like "%$term%") or (Skins.patternName like "%$term%")
            }
        }
        
        val skins = query.map { rowToSkin(it) }
        
        if (skins.isEmpty()) return@dbQuery emptyList()

        // Fetch all prices for filtered skins
        val skinIds = skins.map { it.skinId }
        val pricesBySkin = SkinPrices.selectAll().where { SkinPrices.skinId inList skinIds }
            .mapNotNull { r ->
                val wear = CSWear.fromId(r[SkinPrices.wearId]) ?: return@mapNotNull null
                val skinPrice = SkinPrice(
                    r[SkinPrices.id],
                    r[SkinPrices.skinId],
                    WearCondition(r[SkinPrices.wearId], ""),
                    r[SkinPrices.price],
                    r[SkinPrices.quantity]
                )
                r[SkinPrices.skinId] to (wear to skinPrice)
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { entry ->
                entry.value.associate { it.first to it.second }.toMutableMap()
            }

        skins.map { skin ->
            val latest = pricesBySkin[skin.skinId]
            if (!latest.isNullOrEmpty()) skin.copy(price = latest) else skin
        }
    }

    override suspend fun findByWeapon(weaponId: String): List<SkinDTO> = dbQuery {
        Skins.selectAll().where { Skins.weaponId eq weaponId }
            .map { rowToSkin(it) }
    }

    override suspend fun findByRarity(rarityId: String): List<SkinDTO> = dbQuery {
        Skins.selectAll().where { Skins.rarityId eq rarityId }
            .map { rowToSkin(it) }
    }

    override suspend fun findWithDetails(skinId: String): SkinWithDetails? = dbQuery {
        Skins.leftJoin(Collections)
            .leftJoin(Weapons)
            .leftJoin(Rarities)
            .selectAll().where { Skins.skinId eq skinId }
            .map { rowToSkinWithDetails(it) }
            .singleOrNull()
    }

    override suspend fun findAllWithDetails(): List<SkinWithDetails> = dbQuery {
        Skins.leftJoin(Collections)
            .leftJoin(Weapons)
            .leftJoin(Rarities)
            .selectAll()
            .map { rowToSkinWithDetails(it) }
    }

    override suspend fun update(skinDTO: SkinDTO): Boolean = dbQuery {
        Skins.update({ Skins.skinId eq skinDTO.skinId }) {
            it[collectionId] = skinDTO.collectionId
            it[name] = skinDTO.name
            it[weaponId] = ensureWeaponExists(skinDTO.weapon)
            it[patternId] = skinDTO.patternId
            it[patternName] = skinDTO.patternName
            it[minFloat] = skinDTO.minFloat
            it[maxFloat] = skinDTO.maxFloat
            it[rarityId] = ensureRarityExists(skinDTO.rarity)
            it[stattrak] = skinDTO.stattrak
            it[image] = skinDTO.image
        } > 0
    }

    override suspend fun delete(skinId: String): Boolean = dbQuery {
        Skins.deleteWhere { Skins.skinId eq skinId } > 0
    }

    override suspend fun deleteAll(): Boolean = dbQuery {
        Skins.deleteAll() > 0
    }

    private fun rowToSkin(row: ResultRow) = SkinDTO(
        skinId = row[Skins.skinId],
        collectionId = row[Skins.collectionId],
        name = row[Skins.name],
        weapon = run {
            val wid = row[Skins.weaponId]
            Weapons.selectAll().where { Weapons.weaponId eq wid!! }
                .map { Weapon(weaponId = it[Weapons.weaponId], name = it[Weapons.name], image = it[Weapons.image]) }
                .singleOrNull() ?: Weapon(weaponId = wid!!, name = "", image = "")
        },
        patternId = row[Skins.patternId],
        patternName = row[Skins.patternName],
        minFloat = row[Skins.minFloat],
        maxFloat = row[Skins.maxFloat],
        rarity = run {
            val rid = row[Skins.rarityId]
            Rarities.selectAll().where { Rarities.rarityId eq rid!! }
                .map {
                    Rarity(
                        rarityId = it[Rarities.rarityId],
                        name = it[Rarities.name],
                        colorHex = it[Rarities.colorHex]
                    )
                }
                .singleOrNull() ?: Rarity(rarityId = rid!!, name = "", colorHex = "")
        },
        stattrak = row[Skins.stattrak],
        image = row[Skins.image]
    )

    private fun rowToSkinWithDetails(row: ResultRow): SkinWithDetails {
        val skin = rowToSkin(row)

        val collection = row.getOrNull(Collections.collectionId)?.let {
            Collection(
                collectionId = row[Collections.collectionId],
                name = row[Collections.name],
                image = row[Collections.image]
            )
        }

        val weapon = row.getOrNull(Weapons.weaponId)?.let {
            Weapon(
                weaponId = row[Weapons.weaponId],
                name = row[Weapons.name],
                image = row[Weapons.image]
            )
        }

        val rarity = row.getOrNull(Rarities.rarityId)?.let {
            Rarity(
                rarityId = row[Rarities.rarityId],
                name = row[Rarities.name],
                colorHex = row[Rarities.colorHex]
            )
        }

        return SkinWithDetails(skin, collection, weapon, rarity)
    }
}


interface SkinRepositoryInterface {
    suspend fun create(skinDTO: SkinDTO): SkinDTO
    suspend fun findById(skinId: String): SkinDTO?
    suspend fun findAll(): List<SkinDTO>
    suspend fun findByCollection(collectionId: String): List<SkinDTO>
    suspend fun findByCollectionWithPrice(collectionId: String, stattrak: Boolean = false): List<SkinDTO>
    suspend fun findByWeapon(weaponId: String): List<SkinDTO>
    suspend fun findByRarity(rarityId: String): List<SkinDTO>
    suspend fun findWithDetails(skinId: String): SkinWithDetails?
    suspend fun findAllWithDetails(): List<SkinWithDetails>
    suspend fun update(skinDTO: SkinDTO): Boolean
    suspend fun delete(skinId: String): Boolean
    suspend fun deleteAll(): Boolean
}

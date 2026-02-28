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

    override suspend fun createAll(skinDTOs: List<SkinDTO>) = dbQuery {
        skinDTOs.forEach { skinDTO ->
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
        }
    }


    override suspend fun findById(skinId: String): SkinDTO? = dbQuery {
        val row = Skins.selectAll().where { Skins.skinId eq skinId }.singleOrNull()
            ?: return@dbQuery null
        val wid = row[Skins.weaponId]!!
        val rid = row[Skins.rarityId]!!
        val weaponMap = loadWeapons(listOf(wid))
        val rarityMap = loadRarities(listOf(rid))
        val skin = rowToSkin(row, weaponMap, rarityMap)
        val prices = loadPricesBySkin(listOf(skinId))[skinId]
        if (!prices.isNullOrEmpty()) skin.copy(price = prices) else skin
    }

    override suspend fun findAll(): List<SkinDTO> = dbQuery {
        val rows = Skins.selectAll().toList()
        if (rows.isEmpty()) return@dbQuery emptyList()

        val weaponMap = loadWeapons(rows.mapNotNull { it[Skins.weaponId] }.distinct())
        val rarityMap = loadRarities(rows.mapNotNull { it[Skins.rarityId] }.distinct())
        val skins = rows.map { rowToSkin(it, weaponMap, rarityMap) }

        val pricesBySkin = loadPricesBySkin(skins.map { it.skinId })

        skins.map { skin ->
            val prices = pricesBySkin[skin.skinId]
            if (!prices.isNullOrEmpty()) skin.copy(price = prices) else skin
        }
    }

    override suspend fun findByCollection(collectionId: String): List<SkinDTO> = dbQuery {
        val rows = Skins.selectAll().where { Skins.collectionId eq collectionId }.toList()
        if (rows.isEmpty()) return@dbQuery emptyList()
        val weaponMap = loadWeapons(rows.mapNotNull { it[Skins.weaponId] }.distinct())
        val rarityMap = loadRarities(rows.mapNotNull { it[Skins.rarityId] }.distinct())
        rows.map { rowToSkin(it, weaponMap, rarityMap) }
    }

    override suspend fun findByCollectionWithPrice(collectionId: String, stattrak: Boolean): List<SkinDTO> = dbQuery {
        val rows = Skins.selectAll()
            .where { (Skins.collectionId eq collectionId) and (Skins.stattrak eq stattrak) }
            .toList()
        if (rows.isEmpty()) return@dbQuery emptyList()
        val weaponMap = loadWeapons(rows.mapNotNull { it[Skins.weaponId] }.distinct())
        val rarityMap = loadRarities(rows.mapNotNull { it[Skins.rarityId] }.distinct())
        val skins = rows.map { rowToSkin(it, weaponMap, rarityMap) }
        val pricesBySkin = loadPricesBySkin(skins.map { it.skinId })
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
        
        val rows = query.toList()
        if (rows.isEmpty()) return@dbQuery emptyList()
        val weaponMap = loadWeapons(rows.mapNotNull { it[Skins.weaponId] }.distinct())
        val rarityMap = loadRarities(rows.mapNotNull { it[Skins.rarityId] }.distinct())
        val skins = rows.map { rowToSkin(it, weaponMap, rarityMap) }

        val pricesBySkin = loadPricesBySkin(skins.map { it.skinId })
        skins.map { skin ->
            val latest = pricesBySkin[skin.skinId]
            if (!latest.isNullOrEmpty()) skin.copy(price = latest) else skin
        }
    }

    override suspend fun findByWeapon(weaponId: String): List<SkinDTO> = dbQuery {
        val rows = Skins.selectAll().where { Skins.weaponId eq weaponId }.toList()
        if (rows.isEmpty()) return@dbQuery emptyList()
        val weaponMap = loadWeapons(rows.mapNotNull { it[Skins.weaponId] }.distinct())
        val rarityMap = loadRarities(rows.mapNotNull { it[Skins.rarityId] }.distinct())
        rows.map { rowToSkin(it, weaponMap, rarityMap) }
    }

    override suspend fun findByRarity(rarityId: String): List<SkinDTO> = dbQuery {
        val rows = Skins.selectAll().where { Skins.rarityId eq rarityId }.toList()
        if (rows.isEmpty()) return@dbQuery emptyList()
        val weaponMap = loadWeapons(rows.mapNotNull { it[Skins.weaponId] }.distinct())
        val rarityMap = loadRarities(rows.mapNotNull { it[Skins.rarityId] }.distinct())
        rows.map { rowToSkin(it, weaponMap, rarityMap) }
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

    /**
     * Maps a [ResultRow] to a [SkinDTO] using pre-loaded weapon and rarity lookup maps.
     * This avoids N+1 queries when converting a large result set — callers should build
     * the maps once via [loadWeapons] / [loadRarities] and pass them in.
     */
    private fun rowToSkin(
        row: ResultRow,
        weaponMap: Map<String, Weapon>,
        rarityMap: Map<String, Rarity>
    ): SkinDTO {
        val wid = row[Skins.weaponId]!!
        val rid = row[Skins.rarityId]!!
        return SkinDTO(
            skinId = row[Skins.skinId],
            collectionId = row[Skins.collectionId],
            name = row[Skins.name],
            weapon = weaponMap[wid] ?: Weapon(weaponId = wid, name = "", image = null),
            patternId = row[Skins.patternId],
            patternName = row[Skins.patternName],
            minFloat = row[Skins.minFloat],
            maxFloat = row[Skins.maxFloat],
            rarity = rarityMap[rid] ?: Rarity(rarityId = rid, name = "", colorHex = null),
            stattrak = row[Skins.stattrak],
            image = row[Skins.image]
        )
    }

    // ── Bulk-load helpers ────────────────────────────────────────────────────

    private fun loadWeapons(weaponIds: List<String>): Map<String, Weapon> =
        if (weaponIds.isEmpty()) emptyMap()
        else Weapons.selectAll().where { Weapons.weaponId inList weaponIds }
            .associate { it[Weapons.weaponId] to Weapon(it[Weapons.weaponId], it[Weapons.name], it[Weapons.image]) }

    private fun loadRarities(rarityIds: List<String>): Map<String, Rarity> =
        if (rarityIds.isEmpty()) emptyMap()
        else Rarities.selectAll().where { Rarities.rarityId inList rarityIds }
            .associate { it[Rarities.rarityId] to Rarity(it[Rarities.rarityId], it[Rarities.name], it[Rarities.colorHex]) }

    /**
     * Bulk-loads prices from [SkinPricesCurrent] for the given skin IDs, restricted to the
     * base currency (is_base = true).  Returns a map of skinId → (CSWear → SkinPrice),
     * keeping the most-recently-updated price per wear when multiple sources are present.
     */
    private fun loadPricesBySkin(skinIds: List<String>): Map<String, MutableMap<CSWear, SkinPrice>> {
        if (skinIds.isEmpty()) return emptyMap()
        return SkinPricesCurrent
            .join(Currencies, JoinType.INNER, SkinPricesCurrent.currencyId, Currencies.id)
            .selectAll()
            .where { (SkinPricesCurrent.skinId inList skinIds) and (Currencies.isBase eq true) }
            .orderBy(SkinPricesCurrent.updatedAt to SortOrder.DESC)
            .mapNotNull { r ->
                val wear = CSWear.fromId(r[SkinPricesCurrent.wearId]) ?: return@mapNotNull null
                val skinPrice = SkinPrice(
                    r[SkinPricesCurrent.skinId],
                    WearCondition(r[SkinPricesCurrent.wearId], ""),
                    r[SkinPricesCurrent.sourceId],
                    r[SkinPricesCurrent.currencyId],
                    r[SkinPricesCurrent.price],
                    r[SkinPricesCurrent.quantity]
                )
                r[SkinPricesCurrent.skinId] to (wear to skinPrice)
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { entry ->
                entry.value.distinctBy { it.first }
                    .associate { it.first to it.second }
                    .toMutableMap()
            }
    }

    private fun rowToSkinWithDetails(row: ResultRow): SkinWithDetails {
        val wid = row[Skins.weaponId]!!
        val rid = row[Skins.rarityId]!!

        val collection = row.getOrNull(Collections.collectionId)?.let {
            Collection(
                collectionId = row[Collections.collectionId],
                name = row[Collections.name],
                image = row[Collections.image]
            )
        }

        val weapon = row.getOrNull(Weapons.weaponId)?.let {
            Weapon(weaponId = row[Weapons.weaponId], name = row[Weapons.name], image = row[Weapons.image])
        } ?: Weapon(weaponId = wid, name = "", image = null)

        val rarity = row.getOrNull(Rarities.rarityId)?.let {
            Rarity(rarityId = row[Rarities.rarityId], name = row[Rarities.name], colorHex = row[Rarities.colorHex])
        } ?: Rarity(rarityId = rid, name = "", colorHex = null)

        val skin = rowToSkin(row, mapOf(wid to weapon), mapOf(rid to rarity))
        return SkinWithDetails(skin, collection, weapon, rarity)
    }
}


interface SkinRepositoryInterface {
    suspend fun create(skinDTO: SkinDTO): SkinDTO
    suspend fun createAll(skinDTOs: List<SkinDTO>)
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

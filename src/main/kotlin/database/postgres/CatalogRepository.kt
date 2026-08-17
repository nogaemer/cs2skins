package database.postgres

import java.math.BigDecimal
import java.sql.Types
import java.time.OffsetDateTime
import javax.sql.DataSource

class CatalogRepository(
    private val dataSource: DataSource
) {

    fun upsertCollection(collection: Collection): Long {
        val id = KeyDerivation.deterministicId(collection.externalId)
        val sql = """
            INSERT INTO collections (id, game_id, external_id, name, image_url)
            SELECT ?, id, ?, ?, ?
            FROM games
            WHERE code = 'cs2'
            ON CONFLICT (game_id, external_id)
            DO UPDATE SET
                name = EXCLUDED.name,
                image_url = EXCLUDED.image_url
            RETURNING id
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.setString(2, collection.externalId)
                statement.setString(3, collection.name)
                setNullableString(statement, 4, collection.imageUrl)

                statement.executeQuery().use { result ->
                    check(result.next()) { "Could not upsert collection ${collection.externalId}" }
                    result.getLong("id")
                }
            }
        }
    }

    /** Weapons are keyed directly by external_id -- no derived value needed. */
    fun upsertWeapon(weapon: Weapon): String {
        val sql = """
            INSERT INTO weapons (external_id, game_id, name, image_url)
            SELECT ?, id, ?, ?
            FROM games
            WHERE code = 'cs2'
            ON CONFLICT (game_id, external_id)
            DO UPDATE SET
                name = EXCLUDED.name,
                image_url = EXCLUDED.image_url
            RETURNING external_id
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setString(1, weapon.externalId)
                statement.setString(2, weapon.name)
                setNullableString(statement, 3, weapon.imageUrl)

                statement.executeQuery().use { result ->
                    check(result.next()) { "Could not upsert weapon ${weapon.externalId}" }
                    result.getString("external_id")
                }
            }
        }
    }

    /** Curated fixed id for the 6 tradeup tiers, hash fallback otherwise -- see KeyDerivation. */
    fun upsertRarity(rarity: Rarity): Short {
        val id = KeyDerivation.rarityId(rarity.name, rarity.externalId)
        val sql = """
            INSERT INTO rarities (id, game_id, external_id, name, color_hex, sort_order)
            SELECT ?, id, ?, ?, ?, ?
            FROM games
            WHERE code = 'cs2'
            ON CONFLICT (game_id, external_id)
            DO UPDATE SET
                name = EXCLUDED.name,
                color_hex = EXCLUDED.color_hex,
                sort_order = EXCLUDED.sort_order
            RETURNING id
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setShort(1, id)
                statement.setString(2, rarity.externalId)
                statement.setString(3, rarity.name)
                setNullableString(statement, 4, rarity.colorHex)
                statement.setShort(5, rarity.sortOrder)

                statement.executeQuery().use { result ->
                    check(result.next()) { "Could not upsert rarity ${rarity.externalId}" }
                    result.getShort("id")
                }
            }
        }
    }

    fun upsertItem(
        item: Item,
        collectionDbId: Long,
        weaponDbId: String,
        rarityDbId: Short
    ): Long {
        val id = KeyDerivation.deterministicId(item.externalId)
        val sql = """
            INSERT INTO items (
                id, game_id, external_id, market_hash_name, name, weapon_id,
                collection_id, rarity_id, pattern_id, pattern_name,
                min_float, max_float, stattrak, souvenir, image_url
            )
            SELECT
                ?, id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            FROM games
            WHERE code = 'cs2'
            ON CONFLICT (game_id, external_id)
            DO UPDATE SET
                market_hash_name = EXCLUDED.market_hash_name,
                name = EXCLUDED.name,
                weapon_id = EXCLUDED.weapon_id,
                collection_id = EXCLUDED.collection_id,
                rarity_id = EXCLUDED.rarity_id,
                pattern_id = EXCLUDED.pattern_id,
                pattern_name = EXCLUDED.pattern_name,
                min_float = EXCLUDED.min_float,
                max_float = EXCLUDED.max_float,
                stattrak = EXCLUDED.stattrak,
                souvenir = EXCLUDED.souvenir,
                image_url = EXCLUDED.image_url
            RETURNING id
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.setString(2, item.externalId)
                statement.setString(3, item.marketHashName)
                statement.setString(4, item.name)
                statement.setString(5, weaponDbId)
                statement.setLong(6, collectionDbId)
                statement.setShort(7, rarityDbId)
                setNullableString(statement, 8, item.patternId)
                setNullableString(statement, 9, item.patternName)
                statement.setBigDecimal(10, item.minFloat.toBigDecimal())
                statement.setBigDecimal(11, item.maxFloat.toBigDecimal())
                statement.setBoolean(12, item.stattrak)
                statement.setBoolean(13, item.souvenir)
                setNullableString(statement, 14, item.imageUrl)

                statement.executeQuery().use { result ->
                    check(result.next()) { "Could not upsert item ${item.externalId}" }
                    result.getLong("id")
                }
            }
        }
    }

    fun upsertItemWearAvailability(itemId: Long, wearId: String) {
        val sql = """
            INSERT INTO item_wear_availability (item_id, wear_bucket_id)
            SELECT ?, id
            FROM wear_buckets
            WHERE code = ?
            ON CONFLICT DO NOTHING
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, itemId)
                statement.setString(2, wearId)
                statement.executeUpdate()
            }
        }
    }

    fun upsertCurrentPrice(
        itemId: Long,
        wearId: String,
        priceSourceCode: String,
        averagePrice: BigDecimal,
        volume24h: Int,
        buyPrice: BigDecimal? = null,
        sellPrice: BigDecimal? = null,
        liquidityScore: Double? = null,
        observedAt: OffsetDateTime = OffsetDateTime.now()
    ): Int {
        val sql = """
        INSERT INTO item_current_prices (
            item_id, wear_bucket_id, price_source_id,
            observed_at, buy_price, sell_price, average_price,
            volume_24h, liquidity_score
        )
        SELECT ?, wb.id, ps.id, ?, ?, ?, ?, ?, ?
        FROM wear_buckets wb
        CROSS JOIN price_sources ps
        WHERE wb.code = ? AND ps.code = ?
        ON CONFLICT (item_id, wear_bucket_id, price_source_id)
        DO UPDATE SET
            observed_at = EXCLUDED.observed_at,
            buy_price = EXCLUDED.buy_price,
            sell_price = EXCLUDED.sell_price,
            average_price = EXCLUDED.average_price,
            volume_24h = EXCLUDED.volume_24h,
            liquidity_score = EXCLUDED.liquidity_score
    """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, itemId)
                statement.setObject(2, observedAt)

                if (buyPrice == null) statement.setNull(3, Types.NUMERIC) else statement.setBigDecimal(3, buyPrice)
                if (sellPrice == null) statement.setNull(4, Types.NUMERIC) else statement.setBigDecimal(4, sellPrice)

                statement.setBigDecimal(5, averagePrice)
                statement.setInt(6, volume24h)

                if (liquidityScore == null) {
                    statement.setNull(7, Types.NUMERIC)
                } else {
                    statement.setBigDecimal(7, BigDecimal.valueOf(liquidityScore))
                }

                statement.setString(8, wearId)
                statement.setString(9, priceSourceCode)
                statement.executeUpdate()
            }
        }
    }

    fun findAllCollections(): List<Collection> {
        val sql = "SELECT id, external_id, name, image_url FROM collections".trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val list = mutableListOf<Collection>()
                    while (result.next()) {
                        list.add(
                            Collection(
                                id = result.getLong("id"),
                                externalId = result.getString("external_id"),
                                name = result.getString("name"),
                                imageUrl = result.getString("image_url")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun findAllRaritiesOrdered(): List<Rarity> {
        val sql = """
            SELECT id, external_id, name, color_hex, sort_order
            FROM rarities
            ORDER BY sort_order
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val list = mutableListOf<Rarity>()
                    while (result.next()) {
                        list.add(
                            Rarity(
                                id = result.getShort("id"),
                                externalId = result.getString("external_id"),
                                name = result.getString("name"),
                                colorHex = result.getString("color_hex"),
                                sortOrder = result.getShort("sort_order")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun findItemsByCollection(collectionId: Long, stattrak: Boolean, souvenir: Boolean = false): List<Item> {
        val sql = """
            SELECT id, external_id, market_hash_name, name, weapon_id,
                   collection_id, rarity_id, pattern_id, pattern_name,
                   min_float, max_float, stattrak, souvenir, image_url
            FROM items
            WHERE collection_id = ? AND stattrak = ? AND souvenir = ?
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, collectionId)
                statement.setBoolean(2, stattrak)
                statement.setBoolean(3, souvenir)

                statement.executeQuery().use { result ->
                    val list = mutableListOf<Item>()
                    while (result.next()) list.add(mapItemRow(result))
                    list
                }
            }
        }
    }

    fun findItemsByCollectionAndRarity(collectionId: Long, rarityId: Short): List<Item> {
        val sql = """
            SELECT id, external_id, market_hash_name, name, weapon_id,
                   collection_id, rarity_id, pattern_id, pattern_name,
                   min_float, max_float, stattrak, souvenir, image_url
            FROM items
            WHERE collection_id = ? AND rarity_id = ?
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, collectionId)
                statement.setShort(2, rarityId)

                statement.executeQuery().use { result ->
                    val list = mutableListOf<Item>()
                    while (result.next()) list.add(mapItemRow(result))
                    list
                }
            }
        }
    }

    fun findCurrentPrice(itemId: Long, wearBucketId: Short): CurrentPrice? {
        val sql = """
            SELECT item_id, wear_bucket_id, price_source_id,
                   observed_at, average_price, volume_24h
            FROM item_current_prices
            WHERE item_id = ? AND wear_bucket_id = ?
            ORDER BY observed_at DESC
            LIMIT 1
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, itemId)
                statement.setShort(2, wearBucketId)

                statement.executeQuery().use { result ->
                    if (!result.next()) return@use null

                    CurrentPrice(
                        itemId = result.getLong("item_id"),
                        wearBucketId = result.getShort("wear_bucket_id"),
                        priceSourceId = result.getShort("price_source_id"),
                        observedAt = result.getObject("observed_at", OffsetDateTime::class.java),
                        averagePrice = result.getBigDecimal("average_price"),
                        volume24h = result.getInt("volume_24h")
                    )
                }
            }
        }
    }

    fun findAllWearBuckets(): List<WearBucket> {
        val sql = """
            SELECT id, code, display_name, min_float, max_float, generation_min_float, probability
            FROM wear_buckets
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val list = mutableListOf<WearBucket>()
                    while (result.next()) {
                        list.add(
                            WearBucket(
                                id = result.getShort("id"),
                                code = result.getString("code"),
                                displayName = result.getString("display_name"),
                                minFloat = result.getDouble("min_float"),
                                maxFloat = result.getDouble("max_float"),
                                generationMinFloat = result.getDouble("generation_min_float"),
                                probability = result.getDouble("probability")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun findAllItems(): List<Item> {
        val sql = """
            SELECT id, external_id, market_hash_name, name, weapon_id,
                   collection_id, rarity_id, pattern_id, pattern_name,
                   min_float, max_float, stattrak, souvenir, image_url
            FROM items
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val list = mutableListOf<Item>()
                    while (result.next()) list.add(mapItemRow(result))
                    list
                }
            }
        }
    }

    fun findGameId(code: String): Short {
        val sql = "SELECT id FROM games WHERE code = ?"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setString(1, code)
                statement.executeQuery().use { result ->
                    check(result.next()) { "Game not found: $code" }
                    result.getShort("id")
                }
            }
        }
    }

    /** weapon_id is TEXT now (weapons.external_id) -- plain getString, no nullable-Short handling needed. */
    private fun mapItemRow(result: java.sql.ResultSet): Item = Item(
        id = result.getLong("id"),
        externalId = result.getString("external_id"),
        marketHashName = result.getString("market_hash_name"),
        name = result.getString("name"),
        weaponId = result.getString("weapon_id"),
        collectionId = getNullableLong(result, "collection_id"),
        rarityId = getNullableShort(result, "rarity_id"),
        patternId = result.getString("pattern_id"),
        patternName = result.getString("pattern_name"),
        minFloat = result.getDouble("min_float"),
        maxFloat = result.getDouble("max_float"),
        stattrak = result.getBoolean("stattrak"),
        souvenir = result.getBoolean("souvenir"),
        imageUrl = result.getString("image_url")
    )

    data class PriceSource(val id: Short, val code: String, val name: String, val currencyCode: String)

// (append inside class CatalogRepository)

    data class ItemUpsertRow(
        val item: Item,
        val collectionId: Long,
        val weaponId: String,
        val rarityId: Short
    )

    data class ItemWearRow(val itemId: Long, val wearCode: String)

    data class CurrentPriceRow(
        val itemId: Long,
        val wearCode: String,
        val priceSourceCode: String,
        val averagePrice: BigDecimal,
        val volume24h: Int,
        val buyPrice: BigDecimal? = null,
        val sellPrice: BigDecimal? = null,
        val liquidityScore: Double? = null,
        val observedAt: OffsetDateTime = OffsetDateTime.now()
    )

    fun findAllPriceSources(): List<PriceSource> {
        val sql = "SELECT id, code, name, currency_code FROM price_sources".trimIndent()
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val list = mutableListOf<PriceSource>()
                    while (result.next()) {
                        list.add(
                            PriceSource(
                                id = result.getShort("id"),
                                code = result.getString("code"),
                                name = result.getString("name"),
                                currencyCode = result.getString("currency_code")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun upsertCollectionsBatch(collections: List<Collection>) {
        if (collections.isEmpty()) return
        val gameId = findGameId("cs2")
        collections.distinctBy { it.externalId }.chunked(2000).forEach { upsertCollectionsChunk(it, gameId) }
    }

    private fun upsertCollectionsChunk(chunk: List<Collection>, gameId: Short) {
        val valuesSql = chunk.joinToString(",") { "(?,?,?,?,?)" }
        val sql = """
        INSERT INTO collections (id, game_id, external_id, name, image_url)
        VALUES $valuesSql
        ON CONFLICT (game_id, external_id)
        DO UPDATE SET name = EXCLUDED.name, image_url = EXCLUDED.image_url
    """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                chunk.forEach { c ->
                    statement.setLong(index++, KeyDerivation.deterministicId(c.externalId))
                    statement.setShort(index++, gameId)
                    statement.setString(index++, c.externalId)
                    statement.setString(index++, c.name)
                    setNullableString(statement, index++, c.imageUrl)
                }
                statement.executeUpdate()
            }
        }
    }

    fun upsertWeaponsBatch(weapons: List<Weapon>) {
        if (weapons.isEmpty()) return
        val gameId = findGameId("cs2")
        weapons.distinctBy { it.externalId }.chunked(2000).forEach { upsertWeaponsChunk(it, gameId) }
    }

    private fun upsertWeaponsChunk(chunk: List<Weapon>, gameId: Short) {
        val valuesSql = chunk.joinToString(",") { "(?,?,?,?)" }
        val sql = """
        INSERT INTO weapons (external_id, game_id, name, image_url)
        VALUES $valuesSql
        ON CONFLICT (game_id, external_id)
        DO UPDATE SET name = EXCLUDED.name, image_url = EXCLUDED.image_url
    """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                chunk.forEach { w ->
                    statement.setString(index++, w.externalId)
                    statement.setShort(index++, gameId)
                    statement.setString(index++, w.name)
                    setNullableString(statement, index++, w.imageUrl)
                }
                statement.executeUpdate()
            }
        }
    }

    fun upsertRaritiesBatch(rarities: List<Rarity>) {
        if (rarities.isEmpty()) return
        val gameId = findGameId("cs2")
        rarities.distinctBy { it.externalId }.chunked(2000).forEach { upsertRaritiesChunk(it, gameId) }
    }

    private fun upsertRaritiesChunk(chunk: List<Rarity>, gameId: Short) {
        val valuesSql = chunk.joinToString(",") { "(?,?,?,?,?,?)" }
        val sql = """
        INSERT INTO rarities (id, game_id, external_id, name, color_hex, sort_order)
        VALUES $valuesSql
        ON CONFLICT (game_id, external_id)
        DO UPDATE SET name = EXCLUDED.name, color_hex = EXCLUDED.color_hex, sort_order = EXCLUDED.sort_order
    """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                chunk.forEach { r ->
                    statement.setShort(index++, KeyDerivation.rarityId(r.name, r.externalId))
                    statement.setShort(index++, gameId)
                    statement.setString(index++, r.externalId)
                    statement.setString(index++, r.name)
                    setNullableString(statement, index++, r.colorHex)
                    statement.setShort(index++, r.sortOrder)
                }
                statement.executeUpdate()
            }
        }
    }

    /** ~15 bind params/row -- 1000-row chunks keep every chunk well under Postgres's ~65k param limit. */
    fun upsertItemsBatch(rows: List<ItemUpsertRow>) {
        if (rows.isEmpty()) return
        val gameId = findGameId("cs2")
        rows.distinctBy { it.item.externalId }.chunked(1000).forEach { upsertItemsChunk(it, gameId) }
    }

    private fun upsertItemsChunk(chunk: List<ItemUpsertRow>, gameId: Short) {
        val valuesSql = chunk.joinToString(",") { "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" }
        val sql = """
        INSERT INTO items (
            id, game_id, external_id, market_hash_name, name, weapon_id,
            collection_id, rarity_id, pattern_id, pattern_name,
            min_float, max_float, stattrak, souvenir, image_url
        )
        VALUES $valuesSql
        ON CONFLICT (game_id, external_id)
        DO UPDATE SET
            market_hash_name = EXCLUDED.market_hash_name,
            name = EXCLUDED.name,
            weapon_id = EXCLUDED.weapon_id,
            collection_id = EXCLUDED.collection_id,
            rarity_id = EXCLUDED.rarity_id,
            pattern_id = EXCLUDED.pattern_id,
            pattern_name = EXCLUDED.pattern_name,
            min_float = EXCLUDED.min_float,
            max_float = EXCLUDED.max_float,
            stattrak = EXCLUDED.stattrak,
            souvenir = EXCLUDED.souvenir,
            image_url = EXCLUDED.image_url
    """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                chunk.forEach { row ->
                    statement.setLong(index++, KeyDerivation.deterministicId(row.item.externalId))
                    statement.setShort(index++, gameId)
                    statement.setString(index++, row.item.externalId)
                    statement.setString(index++, row.item.marketHashName)
                    statement.setString(index++, row.item.name)
                    statement.setString(index++, row.weaponId)
                    statement.setLong(index++, row.collectionId)
                    statement.setShort(index++, row.rarityId)
                    setNullableString(statement, index++, row.item.patternId)
                    setNullableString(statement, index++, row.item.patternName)
                    statement.setBigDecimal(index++, row.item.minFloat.toBigDecimal())
                    statement.setBigDecimal(index++, row.item.maxFloat.toBigDecimal())
                    statement.setBoolean(index++, row.item.stattrak)
                    statement.setBoolean(index++, row.item.souvenir)
                    setNullableString(statement, index++, row.item.imageUrl)
                }
                statement.executeUpdate()
            }
        }
    }

    fun upsertItemWearAvailabilityBatch(rows: List<ItemWearRow>) {
        if (rows.isEmpty()) return
        val wearIdByCode = findAllWearBuckets().associate { it.code to it.id }
        rows.mapNotNull { r -> wearIdByCode[r.wearCode]?.let { r.itemId to it } }
            .distinct()
            .chunked(3000)
            .forEach { upsertItemWearAvailabilityChunk(it) }
    }

    private fun upsertItemWearAvailabilityChunk(chunk: List<Pair<Long, Short>>) {
        val valuesSql = chunk.joinToString(",") { "(?,?)" }
        val sql = """
        INSERT INTO item_wear_availability (item_id, wear_bucket_id)
        VALUES $valuesSql
        ON CONFLICT DO NOTHING
    """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                chunk.forEach { (itemId, wearBucketId) ->
                    statement.setLong(index++, itemId)
                    statement.setShort(index++, wearBucketId)
                }
                statement.executeUpdate()
            }
        }
    }

    fun upsertCurrentPricesBatch(rows: List<CurrentPriceRow>) {
        if (rows.isEmpty()) return
        val wearIdByCode = findAllWearBuckets().associate { it.code to it.id }
        val sourceIdByCode = findAllPriceSources().associate { it.code to it.id }

        val resolved = rows.mapNotNull { r ->
            val wearId = wearIdByCode[r.wearCode] ?: return@mapNotNull null
            val sourceId = sourceIdByCode[r.priceSourceCode] ?: return@mapNotNull null
            ResolvedPriceRow(r.itemId, wearId, sourceId, r.observedAt, r.buyPrice, r.sellPrice, r.averagePrice, r.volume24h, r.liquidityScore)
        }
        resolved.chunked(2000).forEach { upsertCurrentPricesChunk(it) }
    }

    private data class ResolvedPriceRow(
        val itemId: Long, val wearBucketId: Short, val priceSourceId: Short,
        val observedAt: OffsetDateTime, val buyPrice: BigDecimal?, val sellPrice: BigDecimal?,
        val averagePrice: BigDecimal, val volume24h: Int, val liquidityScore: Double?
    )

    private fun upsertCurrentPricesChunk(chunk: List<ResolvedPriceRow>) {
        val valuesSql = chunk.joinToString(",") { "(?,?,?,?,?,?,?,?,?)" }
        val sql = """
        INSERT INTO item_current_prices (
            item_id, wear_bucket_id, price_source_id, observed_at,
            buy_price, sell_price, average_price, volume_24h, liquidity_score
        )
        VALUES $valuesSql
        ON CONFLICT (item_id, wear_bucket_id, price_source_id)
        DO UPDATE SET
            observed_at = EXCLUDED.observed_at,
            buy_price = EXCLUDED.buy_price,
            sell_price = EXCLUDED.sell_price,
            average_price = EXCLUDED.average_price,
            volume_24h = EXCLUDED.volume_24h,
            liquidity_score = EXCLUDED.liquidity_score
    """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                chunk.forEach { r ->
                    statement.setLong(index++, r.itemId)
                    statement.setShort(index++, r.wearBucketId)
                    statement.setShort(index++, r.priceSourceId)
                    statement.setObject(index++, r.observedAt)
                    if (r.buyPrice == null) statement.setNull(index++, Types.NUMERIC) else statement.setBigDecimal(index++, r.buyPrice)
                    if (r.sellPrice == null) statement.setNull(index++, Types.NUMERIC) else statement.setBigDecimal(index++, r.sellPrice)
                    statement.setBigDecimal(index++, r.averagePrice)
                    statement.setInt(index++, r.volume24h)
                    if (r.liquidityScore == null) statement.setNull(index++, Types.NUMERIC) else statement.setBigDecimal(index++, BigDecimal.valueOf(r.liquidityScore))
                }
                statement.executeUpdate()
            }
        }
    }

    private fun getNullableShort(result: java.sql.ResultSet, column: String): Short? {
        val value = result.getInt(column)
        return if (result.wasNull()) null else value.toShort()
    }

    private fun getNullableLong(result: java.sql.ResultSet, column: String): Long? {
        val value = result.getLong(column)
        return if (result.wasNull()) null else value
    }

    private fun setNullableString(statement: java.sql.PreparedStatement, index: Int, value: String?) {
        if (value == null) statement.setNull(index, Types.VARCHAR) else statement.setString(index, value)
    }
}
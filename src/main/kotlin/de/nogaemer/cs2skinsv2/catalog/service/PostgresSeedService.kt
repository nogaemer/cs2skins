package de.nogaemer.cs2skinsv2.catalog.service

import de.nogaemer.cs2skinsv2.catalog.model.Collection
import de.nogaemer.cs2skinsv2.catalog.model.Item
import de.nogaemer.cs2skinsv2.catalog.model.Rarity
import de.nogaemer.cs2skinsv2.catalog.model.Weapon
import de.nogaemer.cs2skinsv2.catalog.repository.CatalogRepository
import de.nogaemer.cs2skinsv2.catalog.repository.KeyDerivation
import de.nogaemer.cs2skinsv2.catalog.repository.WearMapping
import org.json.JSONArray
import org.json.JSONObject
import org.springframework.stereotype.Service
import java.net.URI

@Service
class PostgresSeedService(
    private val repository: CatalogRepository
) {

    private val rarityOrder = mapOf(
        "Consumer Grade" to 0.toShort(),
        "Industrial Grade" to 1.toShort(),
        "Mil-Spec Grade" to 2.toShort(),
        "Restricted" to 3.toShort(),
        "Classified" to 4.toShort(),
        "Covert" to 5.toShort()
    )

    private data class ParsedSkin(
        val json: JSONObject,
        val collection: Collection,
        val weapon: Weapon,
        val rarity: Rarity,
        val baseName: String,
        val baseExternalId: String
    )

    suspend fun seedCollections() {
        val url = URI("https://raw.githubusercontent.com/ByMykel/CSGO-API/main/public/api/en/collections.json").toURL()
        val collectionsJson = JSONArray(url.readText())

        val collections = (0 until collectionsJson.length()).map { index ->
            val json = collectionsJson.getJSONObject(index)
            Collection(
                id = 0, // ignored -- deterministic id derived from externalId inside the repository
                externalId = json.getString("id"),
                name = json.getString("name"),
                imageUrl = json.optString("image", null)
            )
        }

        repository.upsertCollectionsBatch(collections)
        println("Seeded ${collections.size} collections")
    }

    suspend fun seedSkins() {
        val skinsUrl = URI("https://raw.githubusercontent.com/ByMykel/CSGO-API/main/public/api/en/skins.json").toURL()
        val skins = JSONArray(skinsUrl.readText())
        println("Total skins in source JSON: ${skins.length()}")

        // ---- Pass 1: parse everything into memory. No DB access yet. ----
        val parsed = mutableListOf<ParsedSkin>()
        var skipped = 0
        var failed = 0

        for (index in 0 until skins.length()) {
            val json = skins.getJSONObject(index)
            val collectionJson = json.optJSONArray("collections")?.optJSONObject(0)
            val weaponJson = json.optJSONObject("weapon")
            val rarityJson = json.optJSONObject("rarity")

            if (collectionJson == null || weaponJson == null || rarityJson == null) {
                skipped++
                continue
            }

            try {
                val collection = Collection(
                    id = 0,
                    externalId = collectionJson.getString("id"),
                    name = collectionJson.optString("name", ""),
                    imageUrl = collectionJson.optString("image", null)
                )
                val weapon = Weapon(
                    externalId = weaponJson.getString("id"),
                    name = weaponJson.getString("name"),
                    imageUrl = weaponJson.optString("image", null)
                )
                val rarityName = rarityJson.getString("name")
                val rarity = Rarity(
                    id = 0,
                    externalId = rarityJson.getString("id"),
                    name = rarityName,
                    colorHex = rarityJson.optString("color", null)?.removePrefix("#"),
                    sortOrder = rarityOrder[rarityName] ?: 99
                )

                parsed.add(
                    ParsedSkin(
                        json = json,
                        collection = collection,
                        weapon = weapon,
                        rarity = rarity,
                        baseName = json.getString("name"),
                        baseExternalId = json.getString("id")
                    )
                )
            } catch (e: Exception) {
                failed++
                if (failed <= 5) {
                    println("FAILED parsing skin index $index (id=${json.optString("id")}): ${e.message}")
                }
            }
        }

        println("Parsed ${parsed.size} skins, skipped $skipped, failed $failed")

        // ---- Pass 2: batch-upsert dimensions once, deduped. ----
        repository.upsertCollectionsBatch(parsed.map { it.collection })
        repository.upsertWeaponsBatch(parsed.map { it.weapon })
        repository.upsertRaritiesBatch(parsed.map { it.rarity })


        val itemRowsByMarketHashName = linkedMapOf<String, CatalogRepository.ItemUpsertRow>()
        val wearCodesByItemId = mutableMapOf<Long, MutableList<String>>()
        var duplicateMarketHashNames = 0

        fun addItemWithWears(item: Item, collectionId: Long, weaponId: String, rarityId: Short, json: JSONObject) {
            if (itemRowsByMarketHashName.containsKey(item.marketHashName)) {
                duplicateMarketHashNames++
                return
            }
            itemRowsByMarketHashName[item.marketHashName] =
                CatalogRepository.ItemUpsertRow(item, collectionId, weaponId, rarityId)

            val itemId = KeyDerivation.deterministicId(item.externalId)
            val wears = json.optJSONArray("wears") ?: return
            val codes = wearCodesByItemId.getOrPut(itemId) { mutableListOf() }
            for (i in 0 until wears.length()) {
                val wear = wears.optJSONObject(i) ?: continue
                codes.add(WearMapping.fromDisplayName(wear.getString("name")))
            }
        }

        parsed.forEach { p ->
            val collectionId = KeyDerivation.deterministicId(p.collection.externalId)
            val rarityId = KeyDerivation.rarityId(p.rarity.name, p.rarity.externalId)
            val patternJson = p.json.optJSONObject("pattern")

            // Every JSON entry always represents the normal-quality listing.
            // stattrak/souvenir are flags meaning an additional variant
            // exists for this same skin, not that this object IS that
            // variant -- derive the normal item first, then conditionally
            // derive the variants from it.
            val normalItem = Item(
                id = 0,
                externalId = p.baseExternalId,
                marketHashName = p.baseName,
                name = p.baseName,
                weaponId = p.weapon.externalId,
                collectionId = collectionId,
                rarityId = rarityId,
                patternId = patternJson?.optString("id", null),
                patternName = patternJson?.optString("name", null),
                minFloat = p.json.optDouble("min_float", 0.0),
                maxFloat = p.json.optDouble("max_float", 0.0),
                stattrak = false,
                souvenir = false,
                imageUrl = p.json.optString("image", null)
            )
            addItemWithWears(normalItem, collectionId, p.weapon.externalId, rarityId, p.json)

            if (p.json.optBoolean("stattrak", false)) {
                val stattrakItem = normalItem.copy(
                    externalId = "${p.baseExternalId}-stattrak",
                    marketHashName = "StatTrak\u2122 ${p.baseName}",
                    stattrak = true
                )
                addItemWithWears(stattrakItem, collectionId, p.weapon.externalId, rarityId, p.json)
            }

            if (p.json.optBoolean("souvenir", false)) {
                val souvenirItem = normalItem.copy(
                    externalId = "${p.baseExternalId}-souvenir",
                    marketHashName = "Souvenir ${p.baseName}",
                    souvenir = true
                )
                addItemWithWears(souvenirItem, collectionId, p.weapon.externalId, rarityId, p.json)
            }
        }

        val itemRows = itemRowsByMarketHashName.values.toList()
        val wearRows = itemRows.flatMap { row ->
            val itemId = KeyDerivation.deterministicId(row.item.externalId)
            wearCodesByItemId[itemId]?.map { code -> CatalogRepository.ItemWearRow(itemId, code) } ?: emptyList()
        }

        repository.upsertItemsBatch(itemRows)
        repository.upsertItemWearAvailabilityBatch(wearRows)

        println(
            "Upserted ${itemRows.size} items ($duplicateMarketHashNames duplicate market_hash_name skipped), " +
                    "${wearRows.size} wear-availability rows"
        )
    }
}
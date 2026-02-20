package database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URL

class SeedDB {
    val collectionRepo = CollectionRepository()
    val weaponRepo = WeaponRepository()
    val rarityRepo = RarityRepository()
    val wearRepo = WearConditionRepository()
    val skinRepo = SkinRepository()
    val priceRepo = SkinPriceRepository()

    suspend fun seedCollections() = withContext(Dispatchers.IO) {
        collectionRepo.deleteAll()

        val url =
            "https://raw.githubusercontent.com/ByMykel/CSGO-API/main/public/api/en/collections.json"
        val jsonText = URL(url).readText()
        val collections = JSONArray(jsonText)

        for (i in 0 until collections.length()) {
            val collection = collections.getJSONObject(i)

            collectionRepo.create(
                Collection(
                    collectionId = collection.getString("id"),
                    name = collection.getString("name"),
                    image = collection.getString("image")
                )
            )
        }
    }

    /**
     * Seeds skins and prices from remote APIs
     */
    suspend fun seedSkins() = withContext(Dispatchers.IO) {
        skinRepo.deleteAll()

        val skinsUrl =
            "https://raw.githubusercontent.com/ByMykel/CSGO-API/main/public/api/en/skins.json"
        val skinsText = URL(skinsUrl).readText()
        val skins = JSONArray(skinsText)

        val pricesUrl = "https://backend.sih.market/api/v2/items?sortBy=item&appId=730&limit=10000000&offset=0&lang=en"
        val pricesText = URL(pricesUrl).readText()
        val prices = JSONObject(pricesText).getJSONArray("data")

        val seenSkinNames = mutableSetOf<String>()

        for (i in 0 until skins.length()) {
            val skin = skins.getJSONObject(i)
            val skinName = skin.optString("name", "")

            if (skinName.isNotEmpty() && !seenSkinNames.add(skinName) && !skinName.contains("Doppler")) {
                println("WARN: Duplicate skin name detected: $skinName")
            }

            val collections = skin.optJSONArray("collections")
            if (collections == null || collections.isEmpty) continue
            if (collections.length() > 1) println("WARN: Skin ${skin.getString("name")} has multiple collections")
            val collectionId = collections.getJSONObject(0).getString("id")

            if (skin.getBoolean("souvenir")) continue

            val weapon = Weapon(
                skin.optJSONObject("weapon")?.optString("id", null)?: continue,
                skin.optJSONObject("weapon")?.optString("name", null)?: continue,
                ""
            )

            val rarity = Rarity(
                skin.optJSONObject("rarity")?.optString("id", null)?: continue,
                skin.optJSONObject("rarity")?.optString("name", null)?: continue,
                skin.optJSONObject("rarity")?.optString("color", null)?.removePrefix("#")?: continue,
            )

            val stattrak = skin.optBoolean("stattrak", false)
            val stattrackSkinId = skin.getString("id") + "_stattrak"

            val baseSkinDTO = SkinDTO(
                skinId = skin.getString("id"),
                collectionId = collectionId,
                name = skin.getString("name"),
                weapon = weapon,
                patternId = skin.optJSONObject("pattern")?.optString("id", null),
                patternName = skin.optJSONObject("pattern")?.optString("name", null),
                minFloat = if (skin.has("min_float") && !skin.isNull("min_float")) skin.getDouble("min_float") else 0.0,
                maxFloat = if (skin.has("max_float") && !skin.isNull("max_float")) skin.getDouble("max_float") else 0.0,
                rarity = rarity,
                stattrak = false,
                image = skin.optString("image", null)
            )

            val skinDTOs = mutableListOf(baseSkinDTO)
            if (stattrak) {
                skinDTOs.add(
                    baseSkinDTO.copy(
                        skinId = stattrackSkinId,
                        stattrak = true
                    )
                )
            }
            skinRepo.createAll(skinDTOs)

            val wears = skin.optJSONArray("wears") ?: continue
            val skinPrices = mutableListOf<SkinPrice>()

            for (i in 0 until wears.length()) {

                val wear = wears.optJSONObject(i) ?: continue
                val skinNameNormal = "${skin.getString("name")} (${wear.getString("name")})"
                val priceObj = prices.binarySearchByItem(skinNameNormal)

                val price = priceObj?.optDouble("steamPrice", 0.0) ?: 0.0
                val weekSales = priceObj?.optInt("weekSales", 0) ?: 0

                val wearId = wear.getString("name").lowercase().replace(" ", "_").replace("-", "_")
                val wearCondition = WearCondition(wearId, wear.getString("name"))

                skinPrices.add(
                    SkinPrice(
                        skinId = skin.getString("id"),
                        wear = wearCondition,
                        price = BigDecimal(price),
                        quantity = weekSales,
                    )
                )

                if (stattrak) {
                    skinPrices.add(
                        SkinPrice(
                            skinId = stattrackSkinId,
                            wear = wearCondition,
                            price = BigDecimal(price),
                            quantity = weekSales,
                        )
                    )
                }
            }
            priceRepo.createAll(skinPrices)
        }
    }

    fun JSONArray.binarySearchByItem(target: String): JSONObject? {
        var low = 0
        var high = this.length() - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val obj = this.getJSONObject(mid)
            val item = obj.optString("item", "")
            val cmp = item.compareTo(target)
            when {
                cmp == 0 -> return obj
                cmp < 0 -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return null
    }
}
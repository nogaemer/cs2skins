package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.*
import database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.CSWear
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class SkinService(
    private val skinRepository: SkinRepository = SkinRepository(),
    private val skinPriceRepository: SkinPriceRepository = SkinPriceRepository()
) {

    suspend fun getAllSkins(): List<SkinResponse> = withContext(Dispatchers.IO) {
        val skins = skinRepository.findAll()
        skins.map { mapToSkinResponse(it) }
    }

    suspend fun getSkinById(skinId: String): SkinResponse? = withContext(Dispatchers.IO) {
        val skin = skinRepository.findById(skinId)
        skin?.let { mapToSkinResponse(it) }
    }

    suspend fun searchSkins(filter: SkinFilterRequest): List<SkinResponse> = withContext(Dispatchers.IO) {
        // Use repository method that pushes filters to database
        var skins = skinRepository.findWithFiltersAndPrices(
            weaponId = filter.weaponId,
            rarityId = filter.rarityId,
            collectionId = filter.collectionId,
            stattrak = filter.stattrak,
            searchTerm = filter.searchTerm
        )

        // Filter by price if provided (must be done in memory since it requires price aggregation)
        if (filter.minPrice != null || filter.maxPrice != null) {
            skins = skins.filter { skin ->
                val prices = skin.price.values.map { it.price }
                if (prices.isEmpty()) return@filter false
                
                val avgPrice = prices.reduce { acc, price -> acc.add(price) }
                    .divide(BigDecimal(prices.size), 2, java.math.RoundingMode.HALF_UP)
                
                val meetsMin = filter.minPrice?.let { avgPrice >= it } ?: true
                val meetsMax = filter.maxPrice?.let { avgPrice <= it } ?: true
                meetsMin && meetsMax
            }
        }

        skins.map { mapToSkinResponse(it) }
    }

    suspend fun getSkinsByWeapon(weaponId: String): List<SkinResponse> = withContext(Dispatchers.IO) {
        val skins = skinRepository.findByWeapon(weaponId)
        skins.map { mapToSkinResponse(it) }
    }

    suspend fun getSkinsByRarity(rarityId: String): List<SkinResponse> = withContext(Dispatchers.IO) {
        val skins = skinRepository.findByRarity(rarityId)
        skins.map { mapToSkinResponse(it) }
    }

    suspend fun getSkinsByCollection(collectionId: String, stattrak: Boolean = false): List<SkinResponse> = withContext(Dispatchers.IO) {
        val skins = skinRepository.findByCollectionWithPrice(collectionId, stattrak)
        skins.map { mapToSkinResponse(it) }
    }

    /**
     * Returns raw price history points for a skin+wear combination, filtered by an optional time range.
     *
     * @param skinId  the skin identifier
     * @param wearId  the wear condition identifier
     * @param fromMs  start of time range (epoch milliseconds), or null for all history
     * @param toMs    end of time range (epoch milliseconds), or null for all history
     */
    suspend fun getSkinPriceHistory(
        skinId: String,
        wearId: String,
        fromMs: Long? = null,
        toMs: Long? = null
    ): List<SkinPriceHistoryResponse> = withContext(Dispatchers.IO) {
        skinPriceRepository.findHistory(skinId, wearId, fromMs, toMs)
            .map { point ->
                SkinPriceHistoryResponse(
                    skinId = point.skinId,
                    wearId = point.wearId,
                    recordedAt = point.recordedAt,
                    price = point.price,
                    quantity = point.quantity
                )
            }
    }

    private fun mapToSkinResponse(skin: SkinDTO): SkinResponse {
        return SkinResponse(
            skinId = skin.skinId,
            name = skin.name,
            collectionId = skin.collectionId,
            collectionName = null, // Would need to join with collections
            weaponId = skin.weapon.weaponId,
            weaponName = skin.weapon.name,
            rarityId = skin.rarity.rarityId,
            rarityName = skin.rarity.name,
            rarityColor = skin.rarity.colorHex,
            stattrak = skin.stattrak,
            minFloat = skin.minFloat,
            maxFloat = skin.maxFloat,
            image = skin.image,
            prices = skin.price.mapKeys { (wear, _) -> wear.name }
                .mapValues { (_, price) -> PriceInfo(price.price, price.quantity) }
        )
    }
}


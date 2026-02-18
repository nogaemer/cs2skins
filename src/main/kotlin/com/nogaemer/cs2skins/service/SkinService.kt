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
    private val skinRepository: SkinRepository = SkinRepository()
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
        var skins = skinRepository.findAll()

        filter.weaponId?.let { weaponId ->
            skins = skins.filter { it.weapon.weaponId == weaponId }
        }

        filter.rarityId?.let { rarityId ->
            skins = skins.filter { it.rarity.rarityId == rarityId }
        }

        filter.collectionId?.let { collectionId ->
            skins = skins.filter { it.collectionId == collectionId }
        }

        filter.stattrak?.let { stattrak ->
            skins = skins.filter { it.stattrak == stattrak }
        }

        filter.searchTerm?.let { term ->
            skins = skins.filter { 
                it.name.contains(term, ignoreCase = true) ||
                it.weapon.name.contains(term, ignoreCase = true)
            }
        }

        // Filter by price if provided
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

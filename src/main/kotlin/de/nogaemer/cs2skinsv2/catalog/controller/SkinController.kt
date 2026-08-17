package de.nogaemer.cs2skinsv2.catalog.controller

import de.nogaemer.cs2skinsv2.catalog.dto.*
import de.nogaemer.cs2skinsv2.catalog.repository.CatalogRepository
import de.nogaemer.cs2skinsv2.common.dto.PageRequestParams
import de.nogaemer.cs2skinsv2.common.dto.PageResponse
import de.nogaemer.cs2skinsv2.common.dto.SortSpec
import de.nogaemer.cs2skinsv2.common.exception.BadRequestException
import de.nogaemer.cs2skinsv2.common.exception.NotFoundException
import de.nogaemer.cs2skinsv2.pricing.repository.ItemPriceHistoryReadRepository
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.temporal.ChronoUnit

@RestController
@RequestMapping("/api/v1/skins")
class SkinController(
    private val catalogRepository: CatalogRepository,
    private val priceHistoryReadRepository: ItemPriceHistoryReadRepository
) {
    companion object {
        private val ALLOWED_SORT_FIELDS = setOf("name", "averagePrice", "liquidityScore")
        private val DEFAULT_SORT = SortSpec("name", "asc")
        private const val DEFAULT_WEAR_BUCKET = "field_tested"
    }

    @GetMapping
    fun listSkins(
        @RequestParam(required = false) collectionId: Long?,
        @RequestParam(required = false) rarityId: Short?,
        @RequestParam(required = false) wearBucket: String?,
        @RequestParam(required = false) stattrak: Boolean?,
        @RequestParam(required = false) souvenir: Boolean?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "name,asc") sort: String
    ): PageResponse<SkinSummaryDto> {
        val pageParams = PageRequestParams(page, size)
        val sortSpec = SortSpec.parse(sort, ALLOWED_SORT_FIELDS, DEFAULT_SORT)
        val resolvedWearBucket = wearBucket ?: DEFAULT_WEAR_BUCKET

        val filter = CatalogRepository.SkinFilter(
            collectionId = collectionId,
            rarityId = rarityId,
            wearBucket = resolvedWearBucket,
            stattrak = stattrak,
            souvenir = souvenir,
            search = search
        )

        val totalElements = catalogRepository.countSkins(filter)
        val rows = catalogRepository.findSkinsPaged(filter, sortSpec, pageParams)

        val dtos = rows.map { row ->
            SkinSummaryDto(
                id = row.id,
                name = row.name,
                collectionId = row.collectionId,
                collectionName = row.collectionName,
                rarityId = row.rarityId,
                rarityName = row.rarityName,
                rarityColorHex = row.rarityColorHex,
                imageUrl = row.imageUrl,
                stattrak = row.stattrak,
                souvenir = row.souvenir,
                currentPrice = row.averagePrice?.let {
                    SkinCurrentPriceDto(
                        wearBucket = resolvedWearBucket,
                        averagePrice = it.toDouble(),
                        liquidityScore = row.liquidityScore
                    )
                }
            )
        }

        return PageResponse.of(dtos, pageParams.page, pageParams.size, totalElements)
    }

    @GetMapping("/{itemId}")
    fun getSkin(@PathVariable itemId: Long): SkinDetailDto {
        val detail = catalogRepository.findSkinDetailById(itemId)
            ?: throw NotFoundException("No skin found with id $itemId")

        val wearCodeById = catalogRepository.findAllWearBuckets().associate { it.id to it.code }
        val prices = catalogRepository.findPricesForItem(itemId)

        val pricesByWear = prices.mapNotNull { price ->
            val wearCode = wearCodeById[price.wearBucketId] ?: return@mapNotNull null
            SkinPriceByWearDto(
                wearBucket = wearCode,
                averagePrice = price.averagePrice.toDouble(),
                buyPrice = price.buyPrice?.toDouble(),
                sellPrice = price.sellPrice?.toDouble(),
                liquidityScore = price.liquidityScore,
                spreadPct = price.spreadPct,
                slippagePct = price.slippagePct,
                priceImpact5Pct = price.priceImpact5Pct,
                priceImpact10Pct = price.priceImpact10Pct,
                volatility1d = price.volatility1d,
                volatility7d = price.volatility7d,
                observedAt = price.observedAt
            )
        }

        return SkinDetailDto(
            id = detail.id,
            name = detail.name,
            marketHashName = detail.marketHashName,
            collection = detail.collectionId?.let { SkinCollectionRefDto(it, detail.collectionName ?: "Unknown") },
            rarity = detail.rarityId?.let { SkinRarityRefDto(it, detail.rarityName ?: "Unknown", detail.rarityColorHex) },
            weaponName = detail.weaponName,
            minFloat = detail.minFloat,
            maxFloat = detail.maxFloat,
            stattrak = detail.stattrak,
            souvenir = detail.souvenir,
            imageUrl = detail.imageUrl,
            pricesByWear = pricesByWear
        )
    }

    @GetMapping("/{itemId}/price-history")
    fun getPriceHistory(
        @PathVariable itemId: Long,
        @RequestParam wearBucket: String,
        @RequestParam(defaultValue = "30d") window: String
    ): PriceHistoryResponse {
        // Existence check ensures a genuinely unknown item still 404s here, rather than
        // silently returning an empty history for a typo'd/nonexistent itemId.
        catalogRepository.findSkinDetailById(itemId)
            ?: throw NotFoundException("No skin found with id $itemId")

        val wearBucketId = catalogRepository.findAllWearBuckets()
            .firstOrNull { it.code == wearBucket }
            ?.id
            ?: throw BadRequestException("Unknown wearBucket '$wearBucket'")

        val since = when (window) {
            "1d" -> Instant.now().minus(1, ChronoUnit.DAYS)
            "7d" -> Instant.now().minus(7, ChronoUnit.DAYS)
            "30d" -> Instant.now().minus(30, ChronoUnit.DAYS)
            "90d" -> Instant.now().minus(90, ChronoUnit.DAYS)
            "all" -> Instant.EPOCH
            else -> throw BadRequestException("Invalid window '$window' — allowed values: 1d, 7d, 30d, 90d, all")
        }

        val points = priceHistoryReadRepository.findHistory(itemId, wearBucketId, since)
            .map { PriceHistoryPointDto(it.observedAt, it.averagePrice, it.volume24h, it.liquidityScore) }

        return PriceHistoryResponse(itemId, wearBucket, window, points)
    }
}

package de.nogaemer.cs2skinsv2.tradeup.controller

import de.nogaemer.cs2skinsv2.catalog.model.CurrentPrice
import de.nogaemer.cs2skinsv2.catalog.repository.CatalogRepository
import de.nogaemer.cs2skinsv2.common.dto.PageRequestParams
import de.nogaemer.cs2skinsv2.common.dto.PageResponse
import de.nogaemer.cs2skinsv2.common.dto.SortSpec
import de.nogaemer.cs2skinsv2.common.exception.BadRequestException
import de.nogaemer.cs2skinsv2.common.exception.NotFoundException
import de.nogaemer.cs2skinsv2.tradeup.dto.*
import de.nogaemer.cs2skinsv2.tradeup.repository.BestTradeUpByPairRepository
import de.nogaemer.cs2skinsv2.tradeup.repository.TradeUpOutcomeReadRepository
import de.nogaemer.cs2skinsv2.tradeup.repository.TradeUpRecipeRepository
import de.nogaemer.cs2skinsv2.tradeup.repository.TradeUpSnapshotReadRepository
import de.nogaemer.cs2skinsv2.tradeup.service.RatingCalculator
import de.nogaemer.cs2skinsv2.tradeup.service.SkinMarketMetrics
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Part 1 of 4 -- GET /tradeups only (this sub-checkpoint). detail/best/history endpoints
 * follow in subsequent sub-checkpoints per the implementation plan; this class gets
 * appended to, not replaced, as each lands.
 */
@RestController
@RequestMapping("/api/v1/tradeups")
class TradeUpController(
    private val snapshotReadRepository: TradeUpSnapshotReadRepository,
    private val tradeUpRecipeRepository: TradeUpRecipeRepository,
    private val bestPairRepository: BestTradeUpByPairRepository,
    private val outcomeReadRepository: TradeUpOutcomeReadRepository,
    private val catalogRepository: CatalogRepository
) {
    companion object {
        private val ALLOWED_SORT_FIELDS = setOf("rating", "roi", "profitChance", "expectedValue")
        private val DEFAULT_SORT = SortSpec("rating", "desc")
    }

    @GetMapping
    fun listTradeUps(
        @RequestParam(required = false) collectionId: Long?,
        @RequestParam(required = false, defaultValue = "0") minRating: Double,
        @RequestParam(required = false) minRoi: Double?,
        @RequestParam(required = false, defaultValue = "false") best: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(defaultValue = "rating,desc") sort: String
    ): PageResponse<TradeUpSummaryDto> {
        val pageParams = PageRequestParams(page, size)
        val sortSpec = SortSpec.parse(sort, ALLOWED_SORT_FIELDS, DEFAULT_SORT)

        // Resolve collectionId -> member item IDs once, shared by both query paths below.
        // Postgres side (small, indexed) feeds the filter into whichever table is primary.
        val skinItemIds: Set<Long>? = collectionId?.let {
            catalogRepository.findItemsByCollection(it, stattrak = false, souvenir = false)
                .mapTo(mutableSetOf()) { item -> item.id }
        }

        val recipeIds: List<UUID>
        val totalElements: Long
        val ratingByRecipe: Map<UUID, Double>
        val bestRecipeIdSet: Set<UUID>

        if (best) {
            // best=true: paginate best_tradeup_by_skin_pair directly -- it's already exactly
            // one row per pair, so every result here IS a "best for pair" row by construction.
            totalElements = bestPairRepository.countFiltered(skinItemIds, minRating)
            val bestRows = bestPairRepository.findPagedFiltered(
                skinItemIds, minRating, pageParams.size, pageParams.page * pageParams.size
            )
            recipeIds = bestRows.map { it.bestRecipeId }
            ratingByRecipe = bestRows.associate { it.bestRecipeId to it.bestRating.toDouble() }
            bestRecipeIdSet = recipeIds.toSet() // by definition, every row here is best-for-pair
        } else {
            // best=false: paginate ClickHouse's full snapshot set as the primary source.
            val filter = TradeUpSnapshotReadRepository.SnapshotFilter(
                skinItemIds = skinItemIds,
                minRating = minRating,
                minRoi = minRoi
            )
            totalElements = snapshotReadRepository.count(filter)
            val rows = snapshotReadRepository.findPaged(filter, sortSpec, pageParams)
            recipeIds = rows.map { it.recipeId }
            ratingByRecipe = rows.associate { it.recipeId to it.rating.toDouble() }
            // One batched round trip to flag which of THIS page's rows are best-for-pair --
            // never a per-row query.
            bestRecipeIdSet = bestPairRepository.findBestRecipeIds(recipeIds)
        }

        if (recipeIds.isEmpty()) {
            return PageResponse.of(emptyList(), pageParams.page, pageParams.size, totalElements)
        }

        // Full snapshot rows (for cost/profit/etc.) -- needed even in the best=true path,
        // since best_tradeup_by_skin_pair only denormalizes rating/roi/profitChance, not
        // the full cost/profit breakdown this DTO needs.
        val snapshotsByRecipe = snapshotReadRepository.findByRecipeIds(recipeIds).associateBy { it.recipeId }
        val topOutcomeByRecipe = outcomeReadRepository.findTopOutcomeForRecipes(recipeIds)

        // Batch-resolve every skin referenced across this page (both inputs AND top
        // outcomes) in one round trip -- the N+1 avoidance called out in the implementation plan.
        val allSkinIds = snapshotsByRecipe.values.flatMapTo(mutableSetOf()) {
            listOf(it.skin1ItemId, it.skin2ItemId)
        } + topOutcomeByRecipe.values.map { it.outcomeItemId }
        val itemsById = catalogRepository.findByIds(allSkinIds)
        val wearCodeById = catalogRepository.findAllWearBuckets().associate { it.id to it.code }

        val dtos = recipeIds.mapNotNull { recipeId ->
            val snapshot = snapshotsByRecipe[recipeId] ?: return@mapNotNull null
            val item1 = itemsById[snapshot.skin1ItemId]
            val item2 = itemsById[snapshot.skin2ItemId]
            val topOutcome = topOutcomeByRecipe[recipeId]
            val topOutcomeItem = topOutcome?.let { itemsById[it.outcomeItemId] }

            TradeUpSummaryDto(
                recipeId = recipeId.toString(),
                rating = snapshot.rating.toDouble(),
                roi = snapshot.roi.toDouble(),
                roiWithDropChange = snapshot.roiWithDropChange.toDouble(),
                profitChance = snapshot.profitChance.toDouble(),
                inputCost = snapshot.inputCost,
                inputCostWithDropChange = snapshot.inputCostWithDropChange,
                expectedValue = snapshot.expectedValue,
                profit = snapshot.profitAbs,
                profitWithDropChange = snapshot.profitWithDropChange,
                depthGate = snapshot.depthGate.toDouble(),
                isBestForPair = recipeId in bestRecipeIdSet,
                outcomeCount = snapshot.outcomeCount,
                inputs = listOfNotNull(
                    item1?.let {
                        TradeUpInputRefDto(
                            it.id, it.name, it.imageUrl, snapshot.skin1Count,
                            wearCodeById[snapshot.skin1WearBucketId.toShort()] ?: "unknown"
                        )
                    },
                    item2?.let {
                        TradeUpInputRefDto(
                            it.id, it.name, it.imageUrl, snapshot.skin2Count,
                            wearCodeById[snapshot.skin2WearBucketId.toShort()] ?: "unknown"
                        )
                    }
                ),
                topOutcome = topOutcomeItem?.let {
                    TradeUpOutcomeRefDto(it.id, it.name, it.imageUrl, topOutcome.outcomeProbability.toDouble())
                }
            )
        }

        return PageResponse.of(dtos, pageParams.page, pageParams.size, totalElements)
    }

    @GetMapping("/{recipeId}")
    fun getTradeUp(@PathVariable recipeId: String): TradeUpDetailDto {
        val uuid = try {
            UUID.fromString(recipeId)
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("Invalid recipeId '$recipeId' -- must be a UUID")
        }

        val snapshot = snapshotReadRepository.findByRecipeId(uuid)
            ?: throw NotFoundException("No trade-up snapshot found for recipe $recipeId")
        val recipeMeta = tradeUpRecipeRepository.findMetadataById(uuid)
            ?: throw NotFoundException("No trade-up recipe found with id $recipeId")

        val outcomes = outcomeReadRepository.findLatestOutcomes(uuid)

        val inputMetrics1 = toSkinMarketMetrics(
            catalogRepository.findCurrentPrice(
                snapshot.skin1ItemId,
                snapshot.skin1WearBucketId.toShort()
            )
        )
        val inputMetrics2 = toSkinMarketMetrics(
            catalogRepository.findCurrentPrice(
                snapshot.skin2ItemId,
                snapshot.skin2WearBucketId.toShort()
            )
        )

        val outputMetricsList = outcomes.map { outcome ->
            toSkinMarketMetrics(
                catalogRepository.findCurrentPrice(
                    outcome.outcomeItemId,
                    outcome.outputWearBucketId.toShort()
                )
            )
        }

        val outcomeProbabilities = outcomes.map { it.outcomeProbability.toDouble() }

        val breakdown = RatingCalculator.calculate(
            roiWithDropChange = snapshot.roiWithDropChange.toDouble(),
            profitChance = snapshot.profitChance.toDouble(),
            inputA = inputMetrics1,
            inputB = inputMetrics2,
            requiredQtyA = snapshot.skin1Count,
            requiredQtyB = snapshot.skin2Count,
            outputMetrics = outputMetricsList,
            outcomeProbabilities = outcomeProbabilities
        )

        val ratingBreakdown = TradeUpRatingBreakdownDto(
            roiScore = breakdown.roiScore,
            profitChanceScore = breakdown.profitChanceScore,
            execCostScore = breakdown.execCostScore,
            volScore = breakdown.volScore,
            liquidityScore = breakdown.liquidityScore,
            depthGate = breakdown.depthGate
        )

        val allSkinIds = setOf(snapshot.skin1ItemId, snapshot.skin2ItemId) +
                outcomes.map { it.outcomeItemId }.toSet()
        val itemsById = catalogRepository.findByIds(allSkinIds)

        val wearBuckets = catalogRepository.findAllWearBuckets()
        val wearCodeByShortId = wearBuckets.associate { it.id to it.code }
        val wearCodeByIntId = wearBuckets.associate { it.id.toInt() to it.code }
        val raritiesById = catalogRepository.findAllRaritiesOrdered().associateBy { it.id }

        val isBest = bestPairRepository.findBestRecipeIds(listOf(uuid)).contains(uuid)

        val item1 = itemsById[snapshot.skin1ItemId]
        val item2 = itemsById[snapshot.skin2ItemId]

        val item1Price = catalogRepository.findCurrentPrice(
            itemId = snapshot.skin1ItemId,
            wearBucketId = snapshot.skin1WearBucketId.toShort()
        )
        val item2Price = catalogRepository.findCurrentPrice(
            itemId = snapshot.skin2ItemId,
            wearBucketId = snapshot.skin2WearBucketId.toShort()
        )

        val inputs = listOfNotNull(
            item1?.let {
                TradeUpDetailInputDto(
                    skinId = it.id,
                    name = it.name,
                    imageUrl = it.imageUrl,
                    count = snapshot.skin1Count,
                    wearBucket = wearCodeByIntId[snapshot.skin1WearBucketId] ?: "unknown",
                    float = snapshot.skin1Float.toDouble(),
                    currentPrice = item1Price?.averagePrice?.toDouble()
                )
            },
            item2?.let {
                TradeUpDetailInputDto(
                    skinId = it.id,
                    name = it.name,
                    imageUrl = it.imageUrl,
                    count = snapshot.skin2Count,
                    wearBucket = wearCodeByIntId[snapshot.skin2WearBucketId] ?: "unknown",
                    float = snapshot.skin2Float.toDouble(),
                    currentPrice = item2Price?.averagePrice?.toDouble()
                )
            }
        )

        val outcomeDtos = outcomes.mapNotNull { outcome ->
            val outcomeItem = itemsById[outcome.outcomeItemId] ?: return@mapNotNull null
            TradeUpDetailOutcomeDto(
                skinId = outcomeItem.id,
                name = outcomeItem.name,
                imageUrl = outcomeItem.imageUrl,
                outputFloat = outcome.outputFloat.toDouble(),
                outputWearBucket = wearCodeByIntId[outcome.outputWearBucketId] ?: "unknown",
                probability = outcome.outcomeProbability.toDouble(),
                price = outcome.outcomePrice,
                expectedContribution = outcome.expectedContribution
            )
        }

        val inputRarity = raritiesById[recipeMeta.inputRarityId]
        val outputRarity = raritiesById[recipeMeta.outputRarityId]

        return TradeUpDetailDto(
            recipeId = uuid.toString(),
            inputRarity = inputRarity?.let { TradeUpRarityRefDto(it.id, it.name, it.colorHex) },
            outputRarity = outputRarity?.let { TradeUpRarityRefDto(it.id, it.name, it.colorHex) },
            wearBucket = wearCodeByShortId[recipeMeta.wearBucketId] ?: "unknown",
            allowStattrak = recipeMeta.allowStattrak,
            rating = snapshot.rating.toDouble(),
            roi = snapshot.roi.toDouble(),
            roiWithDropChange = snapshot.roiWithDropChange.toDouble(),
            profitChance = snapshot.profitChance.toDouble(),
            inputCost = snapshot.inputCost,
            inputCostWithDropChange = snapshot.inputCostWithDropChange,
            expectedValue = snapshot.expectedValue,
            profit = snapshot.profitAbs,
            profitWithDropChange = snapshot.profitWithDropChange,
            depthGate = snapshot.depthGate.toDouble(),
            volatilityCombined7d = snapshot.volatilityCombined7d.toDouble(),
            algorithmVersion = snapshot.algorithmVersion,
            snapshotAt = snapshot.snapshotAt,
            isBestForPair = isBest,
            inputs = inputs,
            outcomes = outcomeDtos,
            ratingBreakdown = ratingBreakdown,
            computedAt = snapshot.snapshotAt,
        )


    }

    @GetMapping("/best")
    fun getBestTradeUp(
        @RequestParam skin1Id: Long,
        @RequestParam skin2Id: Long
    ): BestTradeUpResponseDto {
        val row = bestPairRepository.findBySkinPair(skin1Id, skin2Id)
            ?: return BestTradeUpResponseDto(false, null)

        return BestTradeUpResponseDto(
            found = true,
            recipe = BestTradeUpRecipeDto(
                recipeId = row.bestRecipeId.toString(),
                rating = row.bestRating.toDouble(),
                roiWithDropChange = row.bestRoiWithDropChange.toDouble(),
                profitChance = row.bestProfitChance.toDouble(),
                computedAt = row.computedAt
            )
        )
    }

    @GetMapping("/top")
    fun getTopTradeUps(
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") minRating: Double
    ): TopTradeUpListResponse {
        val cappedLimit = limit.coerceIn(1, 200)
        val bestRows = bestPairRepository.findTopN(cappedLimit, minRating)
        val recipeIds = bestRows.map { it.bestRecipeId }
        val snapshotsByRecipe = snapshotReadRepository.findByRecipeIds(recipeIds)
            .associateBy { it.recipeId }

        val allSkinIds = bestRows.flatMap { listOf(it.skin1ItemId, it.skin2ItemId) }.toSet()
        val itemsById = catalogRepository.findByIds(allSkinIds)

        val content = bestRows.mapNotNull { row ->
            val snapshot = snapshotsByRecipe[row.bestRecipeId] ?: return@mapNotNull null
            val item1 = itemsById[row.skin1ItemId]
            val item2 = itemsById[row.skin2ItemId]

            TopTradeUpDto(
                recipeId = row.bestRecipeId.toString(),
                rating = row.bestRating.toDouble(),
                roiWithDropChange = row.bestRoiWithDropChange.toDouble(),
                profitChance = row.bestProfitChance.toDouble(),
                inputs = listOfNotNull(
                    item1?.let { TopTradeUpInputDto(it.id, it.name, it.imageUrl, snapshot.skin1Count) },
                    item2?.let { TopTradeUpInputDto(it.id, it.name, it.imageUrl, snapshot.skin2Count) }
                ),
                computedAt = row.computedAt
            )
        }

        return TopTradeUpListResponse(content)
    }

    @GetMapping("/{recipeId}/history")
    fun getTradeUpHistory(
        @PathVariable recipeId: String,
        @RequestParam(defaultValue = "90d") window: String
    ): TradeUpHistoryResponseDto {
        val uuid = try {
            UUID.fromString(recipeId)
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("Invalid recipeId '$recipeId' — must be a UUID")
        }

        // 404 if recipe does not exist
        tradeUpRecipeRepository.findMetadataById(uuid)
            ?: throw NotFoundException("No trade-up recipe found with id $recipeId")

        val since = when (window) {
            "30d" -> Instant.now().minus(30, ChronoUnit.DAYS)
            "90d" -> Instant.now().minus(90, ChronoUnit.DAYS)
            "1y" -> Instant.now().minus(365, ChronoUnit.DAYS)
            "all" -> Instant.EPOCH
            else -> throw BadRequestException("Invalid window '$window' — allowed values: 30d, 90d, 1y, all")
        }

        val points = snapshotReadRepository.findHistory(uuid, since).map { row ->
            TradeUpHistoryPointDto(
                runId = row.runId,
                snapshotAt = row.snapshotAt,
                rating = row.rating.toDouble(),
                roiWithDropChange = row.roiWithDropChange.toDouble(),
                profitChance = row.profitChance.toDouble(),
                inputCost = row.inputCost
            )
        }

        return TradeUpHistoryResponseDto(
            recipeId = uuid.toString(),
            window = window,
            points = points,
            note = "Points are only present for runs where this recipe was the best candidate for its skin-pair/count/wear-bucket group. Gaps between points are expected and should not be interpolated."
        )
    }

    private fun toSkinMarketMetrics(price: CurrentPrice?): SkinMarketMetrics =
        SkinMarketMetrics(
            liquidityScore = price?.liquidityScore,
            spreadPct = price?.spreadPct,
            slippagePct = price?.slippagePct,
            priceImpact5Pct = price?.priceImpact5Pct,
            priceImpact10Pct = price?.priceImpact10Pct,
            volatility7d = price?.volatility7d
        )

}
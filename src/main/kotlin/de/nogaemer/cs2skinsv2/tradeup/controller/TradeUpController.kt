package de.nogaemer.cs2skinsv2.tradeup.controller

import de.nogaemer.cs2skinsv2.catalog.repository.CatalogRepository
import de.nogaemer.cs2skinsv2.common.dto.PageRequestParams
import de.nogaemer.cs2skinsv2.common.dto.PageResponse
import de.nogaemer.cs2skinsv2.common.dto.SortSpec
import de.nogaemer.cs2skinsv2.tradeup.dto.TradeUpInputRefDto
import de.nogaemer.cs2skinsv2.tradeup.dto.TradeUpOutcomeRefDto
import de.nogaemer.cs2skinsv2.tradeup.dto.TradeUpSummaryDto
import de.nogaemer.cs2skinsv2.tradeup.repository.BestTradeUpByPairRepository
import de.nogaemer.cs2skinsv2.tradeup.repository.TradeUpOutcomeReadRepository
import de.nogaemer.cs2skinsv2.tradeup.repository.TradeUpSnapshotReadRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
}
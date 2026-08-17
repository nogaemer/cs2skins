package de.nogaemer.cs2skinsv2.pricing.repository

import de.nogaemer.cs2skinsv2.config.ClickHouseClientFactory
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Read-side companion to ItemPriceHistoryWriter (which is write-only). Queries
 * tradeups.item_price_history_raw -- the append-only ClickHouse history table
 * (distinct from Postgres' item_current_prices, which only ever holds the latest
 * snapshot) -- backing GET /skins/{id}/price-history.
 */
@Repository
class ItemPriceHistoryReadRepository(
    private val clickHouseClientFactory: ClickHouseClientFactory
) {

    data class PriceHistoryPoint(
        val observedAt: Instant,
        val averagePrice: Double,
        val volume24h: Int,
        val liquidityScore: Float
    )

    fun findHistory(itemId: Long, wearBucketId: Short, since: Instant): List<PriceHistoryPoint> {
        val sql = """
            SELECT observed_at, average_price, volume_24h, liquidity_score
            FROM tradeups.item_price_history_raw
            WHERE item_id = ? AND wear_bucket_id = ? AND observed_at >= ?
            ORDER BY observed_at ASC
        """.trimIndent()

        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, itemId)
                statement.setShort(2, wearBucketId)
                statement.setObject(3, since)
                statement.executeQuery().use { result ->
                    val points = mutableListOf<PriceHistoryPoint>()
                    while (result.next()) {
                        points.add(
                            PriceHistoryPoint(
                                observedAt = result.getObject("observed_at", Instant::class.java),
                                averagePrice = result.getBigDecimal("average_price").toDouble(),
                                volume24h = result.getInt("volume_24h"),
                                liquidityScore = result.getFloat("liquidity_score")
                            )
                        )
                    }
                    points
                }
            }
        }
    }
}
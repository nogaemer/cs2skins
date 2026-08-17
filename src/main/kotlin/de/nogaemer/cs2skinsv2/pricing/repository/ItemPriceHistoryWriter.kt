package de.nogaemer.cs2skinsv2.pricing.repository

import de.nogaemer.cs2skinsv2.config.ClickHouseClientFactory
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.time.Instant

@Repository
class ItemPriceHistoryWriter(
    private val clickHouseClientFactory: ClickHouseClientFactory
) {

    data class PriceHistoryRow(
        val observedAt: Instant,
        val itemId: Long,
        val wearBucketId: Int,
        val priceSourceId: Int,
        val buyPrice: BigDecimal?,
        val sellPrice: BigDecimal?,
        val averagePrice: BigDecimal,
        val volume24h: Int,
        val listings: Int,
        val liquidityScore: Float,
        val currencyCode: String,
        val spreadPct: Float = 0f,
        val slippagePct: Float = 0f,
        val priceImpact5Pct: Float? = null,
        val priceImpact10Pct: Float? = null,
        val volatility1d: Float = 0f,
        val volatility7d: Float = 0f
    )

    fun insertBatch(rows: List<PriceHistoryRow>) {
        if (rows.isEmpty()) return
        clickHouseClientFactory.query { connection: Connection ->
            val sql = """
                INSERT INTO tradeups.item_price_history_raw (
                    observed_at, item_id, wear_bucket_id, price_source_id,
                    buy_price, sell_price, average_price, volume_24h, listings,
                    liquidity_score, currency_code,
                    spread_pct, slippage_pct, price_impact_5_pct, price_impact_10_pct,
                    volatility_1d, volatility_7d
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                rows.forEach { row ->
                    statement.setObject(1, row.observedAt)
                    statement.setLong(2, row.itemId)
                    statement.setInt(3, row.wearBucketId)
                    statement.setInt(4, row.priceSourceId)
                    if (row.buyPrice == null) statement.setNull(5, Types.DECIMAL) else statement.setBigDecimal(
                        5,
                        row.buyPrice
                    )
                    if (row.sellPrice == null) statement.setNull(6, Types.DECIMAL) else statement.setBigDecimal(
                        6,
                        row.sellPrice
                    )
                    statement.setBigDecimal(7, row.averagePrice)
                    statement.setInt(8, row.volume24h)
                    statement.setInt(9, row.listings)
                    statement.setFloat(10, row.liquidityScore)
                    statement.setString(11, row.currencyCode)
                    statement.setFloat(12, row.spreadPct)
                    statement.setFloat(13, row.slippagePct)
                    if (row.priceImpact5Pct == null) statement.setNull(14, Types.FLOAT) else statement.setFloat(
                        14,
                        row.priceImpact5Pct
                    )
                    if (row.priceImpact10Pct == null) statement.setNull(15, Types.FLOAT) else statement.setFloat(
                        15,
                        row.priceImpact10Pct
                    )
                    statement.setFloat(16, row.volatility1d)
                    statement.setFloat(17, row.volatility7d)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }
}
package database.clickhouse

import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant

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
        val currencyCode: String
    )

    fun insertBatch(rows: List<PriceHistoryRow>) {
        if (rows.isEmpty()) return

        clickHouseClientFactory.query { connection: Connection ->
            val sql = """
                INSERT INTO tradeups.item_price_history_raw (
                    observed_at,
                    item_id,
                    wear_bucket_id,
                    price_source_id,
                    buy_price,
                    sell_price,
                    average_price,
                    volume_24h,
                    listings,
                    liquidity_score,
                    currency_code
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            connection.prepareStatement(sql).use { statement ->
                rows.forEach { row ->
                    statement.setObject(1, row.observedAt)
                    statement.setLong(2, row.itemId)
                    statement.setInt(3, row.wearBucketId)
                    statement.setInt(4, row.priceSourceId)

                    if (row.buyPrice == null) {
                        statement.setNull(5, java.sql.Types.DECIMAL)
                    } else {
                        statement.setBigDecimal(5, row.buyPrice)
                    }

                    if (row.sellPrice == null) {
                        statement.setNull(6, java.sql.Types.DECIMAL)
                    } else {
                        statement.setBigDecimal(6, row.sellPrice)
                    }

                    statement.setBigDecimal(7, row.averagePrice)
                    statement.setInt(8, row.volume24h)
                    statement.setInt(9, row.listings)
                    statement.setFloat(10, row.liquidityScore)
                    statement.setString(11, row.currencyCode)
                    statement.addBatch()
                }

                statement.executeBatch()
            }
        }
    }
}
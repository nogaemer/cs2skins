package database.postgres

import database.postgres.CatalogRepository.CurrentPriceRow
import models.CSWear
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

class PriceIngestionService(
    private val repository: CatalogRepository
) {

    private data class Lookup(val item: Item, val wear: CSWear, val key: String)

    fun ingestCurrentPrices() {
        val items = repository.findAllItems()
        println("Loaded ${items.size} items")

        val lookups = items.flatMap { item ->
            CSWear.entries.map { wear -> Lookup(item, wear, "${item.marketHashName} (${wear.displayName})") }
        }
        println("Built ${lookups.size} price lookup keys")

        var matched = 0
        var unmatched = 0
        var samplesShown = 0

        // Outer chunking is unchanged (HTTP batch size, per openskin.dev's
        // limit). What changed: each HTTP batch's writes now go through ONE
        // multi-row upsert instead of up to 500 individual round trips.
        lookups.chunked(500).forEachIndexed { batchIndex, batch ->
            val response = postBatch(batch.map { it.key })
            val data = response.optJSONObject("data") ?: JSONObject()

            val rows = mutableListOf<CurrentPriceRow>()

            batch.forEach { lookup ->
                val entry = data.optJSONObject(lookup.key)
                if (entry == null) {
                    unmatched++
                    if (samplesShown < 5) {
                        println("No price data for ${lookup.key}")
                        samplesShown++
                    }
                    return@forEach
                }

                val steam = entry.optJSONObject("steam")
                val lowestAsk = entry.optJSONObject("lowest_ask")
                val ask = steam?.optDouble("ask", -1.0)?.takeIf { it >= 0.0 }
                    ?: lowestAsk?.optDouble("price", -1.0)?.takeIf { it >= 0.0 }
                val bid = steam?.optDouble("bid", -1.0)?.takeIf { it >= 0.0 }
                val median = steam?.optDouble("median", -1.0)?.takeIf { it >= 0.0 }
                val liquidityScore = steam?.optDouble("liquidity_score", -1.0)?.takeIf { it >= 0.0 }
                val volume24h = steam?.optInt("volume_24h", 0) ?: 0
                val averagePrice = median ?: ask

                if (averagePrice == null) {
                    unmatched++
                    return@forEach
                }

                rows.add(
                    CurrentPriceRow(
                        itemId = lookup.item.id,
                        wearCode = lookup.wear.id,
                        priceSourceCode = "steam",
                        averagePrice = BigDecimal.valueOf(averagePrice),
                        volume24h = volume24h,
                        buyPrice = ask?.let { BigDecimal.valueOf(it) },
                        sellPrice = bid?.let { BigDecimal.valueOf(it) },
                        liquidityScore = liquidityScore
                    )
                )
                matched++
            }

            repository.upsertCurrentPricesBatch(rows)
            println("Batch ${batchIndex + 1}: processed ${batch.size} keys (matched so far: $matched, unmatched so far: $unmatched)")
        }

        println("Prices: matched $matched, unmatched $unmatched")
    }

    private fun postBatch(items: List<String>): JSONObject {
        val url = URI("https://api.openskin.dev/v1/prices/batch").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept-Encoding", "gzip")

        val requestBody = JSONObject().apply { put("items", JSONArray(items)) }
        connection.outputStream.use { output -> output.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "no body"
            error("openskin batch request failed: HTTP $responseCode - $errorBody")
        }

        val isGzip = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
        val text = if (isGzip) {
            GZIPInputStream(connection.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return JSONObject(text)
    }
}
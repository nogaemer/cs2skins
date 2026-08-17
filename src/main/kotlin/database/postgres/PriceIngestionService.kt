package database.postgres

import database.clickhouse.ItemPriceHistoryWriter
import models.CSWear
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.zip.GZIPInputStream

class PriceIngestionService(
    private val repository: CatalogRepository,
    private val priceHistoryWriter: ItemPriceHistoryWriter
) {

    private data class Lookup(val item: Item, val wear: CSWear, val key: String)

    /**
     * Fast bulk price ingestion via POST /v1/prices/batch (500 items/request).
     * REVERTED to its original shape -- the batch endpoint's `steam` object
     * genuinely does not carry spread/slippage/price_impact/volatility (per
     * openskin's own docs: "since this endpoint has no separate metrics
     * object either"). Only bid/ask/median/volume_24h/liquidity_score/
     * sell_order_count live here. Metrics come from ingestSteamMetrics()
     * instead -- see below for why that has to be a separate, slower pass.
     */
    fun ingestCurrentPrices() {
        val items = repository.findAllItems()
        println("Loaded ${items.size} items")

        val wearIdByCode = repository.findAllWearBuckets().associate { it.code to it.id }
        val steamSource = repository.findAllPriceSources().firstOrNull { it.code == "steam" }
            ?: error("Price source 'steam' not found in price_sources table")

        val lookups = items.flatMap { item ->
            CSWear.entries.map { wear -> Lookup(item, wear, "${item.marketHashName} (${wear.displayName})") }
        }
        println("Built ${lookups.size} price lookup keys")

        var matched = 0
        var unmatched = 0
        var samplesShown = 0

        lookups.chunked(500).forEachIndexed { batchIndex, batch ->
            val response = postBatch(batch.map { it.key })
            val data = response.optJSONObject("data") ?: JSONObject()
            val observedAt = Instant.now()

            val currentPriceRows = mutableListOf<CatalogRepository.CurrentPriceRow>()
            val historyRows = mutableListOf<ItemPriceHistoryWriter.PriceHistoryRow>()

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
                // FIX (still valid): the real key is sell_order_count, not "listings".
                val listings = steam?.optInt("sell_order_count", 0) ?: 0
                val averagePrice = median ?: ask

                if (averagePrice == null) {
                    unmatched++
                    return@forEach
                }

                val wearBucketId = wearIdByCode[lookup.wear.id]
                if (wearBucketId == null) {
                    unmatched++
                    return@forEach
                }

                currentPriceRows.add(
                    CatalogRepository.CurrentPriceRow(
                        itemId = lookup.item.id,
                        wearCode = lookup.wear.id,
                        priceSourceCode = "steam",
                        averagePrice = BigDecimal.valueOf(averagePrice),
                        volume24h = volume24h,
                        listings = listings,
                        buyPrice = ask?.let { BigDecimal.valueOf(it) },
                        sellPrice = bid?.let { BigDecimal.valueOf(it) },
                        liquidityScore = liquidityScore
                        // spread/slippage/price_impact/volatility intentionally omitted here --
                        // ingestSteamMetrics() fills those in separately, without clobbering them.
                    )
                )

                historyRows.add(
                    ItemPriceHistoryWriter.PriceHistoryRow(
                        observedAt = observedAt,
                        itemId = lookup.item.id,
                        wearBucketId = wearBucketId.toInt(),
                        priceSourceId = steamSource.id.toInt(),
                        buyPrice = ask?.let { BigDecimal.valueOf(it) },
                        sellPrice = bid?.let { BigDecimal.valueOf(it) },
                        averagePrice = BigDecimal.valueOf(averagePrice),
                        volume24h = volume24h,
                        listings = listings,
                        liquidityScore = (liquidityScore ?: 0.0).toFloat(),
                        currencyCode = steamSource.currencyCode
                    )
                )

                matched++
            }

            repository.upsertCurrentPricesBatch(currentPriceRows)
            priceHistoryWriter.insertBatch(historyRows)
            println("Batch ${batchIndex + 1}: processed ${batch.size} keys (matched so far: $matched, unmatched so far: $unmatched)")
        }

        println("Prices: matched $matched, unmatched $unmatched")
    }

    /**
     * Slow, separate metrics-enrichment pass via GET /v1/prices/steam --
     * the ONLY endpoint (besides GET /v1/steam/orderbook) that returns
     * spread/slippage/price_impact/volatility, and it's single-item only,
     * with no batch variant. This means one HTTP request per (item, wear)
     * pair -- potentially thousands of requests, vs. ~30 for the batch price
     * job. Run this on a much slower cadence than ingestCurrentPrices()
     * (e.g. once a day, or manually) rather than every price refresh --
     * these metrics (7d/30d volatility, depth-ladder pricing) don't change
     * meaningfully minute-to-minute the way raw price does.
     *
     * [delayMillis] throttles requests -- openskin has "no strict rate
     * limits, but mechanisms in place to prevent blatant abuse"; a small
     * per-request delay avoids tripping that while enriching the full catalog.
     *
     * Only UPDATEs the six metric columns via
     * CatalogRepository.updateSteamMetricsBatch -- never touches price/
     * volume/liquidity, which ingestCurrentPrices() already owns.
     */
    fun ingestSteamMetrics(delayMillis: Long = 150) {
        val items = repository.findAllItems()
        val wearIdByCode = repository.findAllWearBuckets().associate { it.code to it.id }
        val lookups = items.flatMap { item ->
            CSWear.entries.map { wear -> Lookup(item, wear, "${item.marketHashName} (${wear.displayName})") }
        }
        println("Enriching metrics for ${lookups.size} item/wear pairs via GET /v1/prices/steam (this will take a while)...")

        var updated = 0
        var skipped = 0
        val updateBatch = mutableListOf<CatalogRepository.SteamMetricsUpdate>()

        lookups.forEachIndexed { index, lookup ->
            val wearBucketId = wearIdByCode[lookup.wear.id]
            if (wearBucketId == null) {
                skipped++
                return@forEachIndexed
            }

            val steam = try {
                getSteamPrice(lookup.key)
            } catch (e: Exception) {
                skipped++
                null
            }

            if (steam != null) {
                val spreadPct = steam.optJSONObject("spread")?.optDouble("percent", -1.0)?.takeIf { it >= 0.0 }
                val slippagePct = steam.optJSONObject("slippage")?.optDouble("percent", -1.0)?.takeIf { it >= 0.0 }
                val priceImpact = steam.optJSONObject("price_impact")
                val priceImpact5Pct = priceImpact?.optJSONObject("5")?.optDouble("pct_above_ask", -1.0)?.takeIf { it >= 0.0 }
                val priceImpact10Pct = priceImpact?.optJSONObject("10")?.optDouble("pct_above_ask", -1.0)?.takeIf { it >= 0.0 }
                val volatility = steam.optJSONObject("volatility")
                val volatility1d = volatility?.optDouble("1d", -1.0)?.takeIf { it >= 0.0 }
                val volatility7d = volatility?.optDouble("7d", -1.0)?.takeIf { it >= 0.0 }

                updateBatch.add(
                    CatalogRepository.SteamMetricsUpdate(
                        itemId = lookup.item.id,
                        wearBucketId = wearBucketId,
                        spreadPct = spreadPct,
                        slippagePct = slippagePct,
                        priceImpact5Pct = priceImpact5Pct,
                        priceImpact10Pct = priceImpact10Pct,
                        volatility1d = volatility1d,
                        volatility7d = volatility7d
                    )
                )
                updated++
            } else {
                skipped++
            }

            if (updateBatch.size >= 200) {
                repository.updateSteamMetricsBatch(updateBatch)
                updateBatch.clear()
            }

            if (index % 500 == 0) {
                println("  ...${index}/${lookups.size} processed (updated: $updated, skipped: $skipped)")
            }

            Thread.sleep(delayMillis)
        }

        if (updateBatch.isNotEmpty()) repository.updateSteamMetricsBatch(updateBatch)
        println("Steam metrics enrichment: updated $updated, skipped $skipped")
    }

    private fun getSteamPrice(itemKey: String): JSONObject? {
    val encoded = URLEncoder.encode(itemKey, Charsets.UTF_8).replace("+", "%20")
    val url = URI("https://api.openskin.dev/v1/prices/steam?item=$encoded").toURL()
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("Connection", "close")

        val responseCode = connection.responseCode

        if (responseCode == 404) {
            println("GET /v1/prices/steam failed for '$itemKey': HTTP 404")
            connection.errorStream?.use { it.readBytes() }
            return null
        }
        if (responseCode !in 200..299) {
            connection.errorStream?.use { it.readBytes() }
            error("GET /v1/prices/steam failed for '$itemKey': HTTP $responseCode")
        }

        val isGzip = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
        val text = if (isGzip) {
            GZIPInputStream(connection.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return JSONObject(text)
    } finally {
        connection.disconnect()
    }
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
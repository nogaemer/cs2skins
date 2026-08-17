package de.nogaemer.cs2skinsv2.tradeup.repository

import de.nogaemer.cs2skinsv2.catalog.repository.KeyDerivation
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.util.*
import javax.sql.DataSource

@Repository
class TradeUpRecipeRepository(
    private val dataSource: DataSource
) {

    data class RecipeInput(
        val gameId: Short,
        val inputRarityId: Short,
        val outputRarityId: Short,
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val skin1Count: Int,
        val skin2Count: Int,
        val wearBucketId: Short,
        val allowStattrak: Boolean
    ) {
        val canonicalHashHex: String by lazy {
            val canonicalString = listOf(
                gameId, inputRarityId, outputRarityId, skin1ItemId, skin2ItemId,
                skin1Count, skin2Count, wearBucketId, allowStattrak
            ).joinToString()
            MessageDigest.getInstance("SHA-256")
                .digest(canonicalString.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Deterministic recipe identity (Phase 2b) -- this IS
         * tradeup_recipes.id and the ClickHouse tradeup_recipe_id join key.
         * Computable entirely in Kotlin; callers never need to wait on a
         * RETURNING round trip to learn it.
         */
        val recipeKey: UUID by lazy { KeyDerivation.recipeKey(canonicalHashHex) }
    }

    /**
     * Upserts many recipes in as few round-trips as possible. No RETURNING,
     * no hash-matching -- every caller already knows RecipeInput.recipeKey
     * before this is ever invoked.
     */
    fun upsertRecipesBatch(recipes: List<RecipeInput>) {
        if (recipes.isEmpty()) return
        recipes.distinctBy { it.recipeKey }.chunked(3000).forEach { upsertChunk(it) }
    }

    private fun upsertChunk(recipes: List<RecipeInput>) {
        val valuesSql = recipes.joinToString(",") { "(?,?,?,?,?,?,?,?,?,?,?)" }
        val sql = """
            INSERT INTO tradeup_recipes (
                id, canonical_hash, game_id, input_rarity_id, output_rarity_id,
                skin_1_item_id, skin_2_item_id, skin_1_count, skin_2_count,
                wear_bucket_id, allow_stattrak
            )
            VALUES $valuesSql
            ON CONFLICT (id) DO NOTHING
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                recipes.forEach { recipe ->
                    statement.setObject(index++, recipe.recipeKey)
                    statement.setBytes(index++, hexToBytes(recipe.canonicalHashHex))
                    statement.setShort(index++, recipe.gameId)
                    statement.setShort(index++, recipe.inputRarityId)
                    statement.setShort(index++, recipe.outputRarityId)
                    statement.setLong(index++, recipe.skin1ItemId)
                    statement.setLong(index++, recipe.skin2ItemId)
                    statement.setShort(index++, recipe.skin1Count.toShort())
                    statement.setShort(index++, recipe.skin2Count.toShort())
                    statement.setShort(index++, recipe.wearBucketId)
                    statement.setBoolean(index++, recipe.allowStattrak)
                }
                statement.executeUpdate()
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

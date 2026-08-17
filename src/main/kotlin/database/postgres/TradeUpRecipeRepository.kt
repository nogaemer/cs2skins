package database.postgres

import java.security.MessageDigest
import javax.sql.DataSource

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
                gameId, inputRarityId, outputRarityId,
                skin1ItemId, skin2ItemId, skin1Count, skin2Count,
                wearBucketId, allowStattrak
            ).joinToString("|")

            MessageDigest.getInstance("SHA-256")
                .digest(canonicalString.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Upserts many recipes in as few round-trips as possible and returns a map from
     * each recipe's canonical hash (hex) to its database id. Chunked to stay well
     * under PostgreSQL's bind-parameter limit per statement.
     */
    fun upsertRecipesBatch(recipes: List<RecipeInput>): Map<String, Long> {
        if (recipes.isEmpty()) return emptyMap()

        val distinctRecipes = recipes.distinctBy { it.canonicalHashHex }
        val result = mutableMapOf<String, Long>()

        distinctRecipes.chunked(3000).forEach { chunk ->
            result.putAll(upsertChunk(chunk))
        }

        return result
    }

    private fun upsertChunk(recipes: List<RecipeInput>): Map<String, Long> {
        val valuesSql = recipes.joinToString(",") { "(?,?,?,?,?,?,?,?,?,?)" }

        val sql = """
            INSERT INTO tradeup_recipes (
                canonical_hash, game_id, input_rarity_id, output_rarity_id,
                skin_1_item_id, skin_2_item_id, skin_1_count, skin_2_count,
                wear_bucket_id, allow_stattrak
            )
            VALUES $valuesSql
            ON CONFLICT (canonical_hash) DO UPDATE SET
                canonical_hash = EXCLUDED.canonical_hash
            RETURNING id, canonical_hash
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                recipes.forEach { recipe ->
                    val hashBytes = hexToBytes(recipe.canonicalHashHex)
                    statement.setBytes(index++, hashBytes)
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

                statement.executeQuery().use { rs ->
                    val idsByHash = mutableMapOf<String, Long>()
                    while (rs.next()) {
                        val hashHex = rs.getBytes("canonical_hash").joinToString("") { "%02x".format(it) }
                        idsByHash[hashHex] = rs.getLong("id")
                    }
                    idsByHash
                }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
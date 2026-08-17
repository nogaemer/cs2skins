package database.postgres

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Deterministic surrogate-key derivation for catalog entities.
 *
 * MUST stay byte-for-byte identical to the SQL functions `deterministic_id`
 * and `deterministic_id_16` in 004_deterministic_catalog_ids.sql. If you
 * change either implementation, change both, or ids computed by the app
 * will disagree with ids computed during migration/backfill.
 */
object KeyDerivation {

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    /** Used for items.id and collections.id -- fits the existing BIGINT column. */
    fun deterministicId(externalId: String): Long =
        ByteBuffer.wrap(sha256(externalId), 0, 8).long

    /** Fallback bucket for rarities NOT in the curated tradeup-tier map below. */
    private fun deterministicId16(externalId: String): Short {
        val h = sha256(externalId)
        val v = ((h[0].toInt() and 0xFF) shl 8) or (h[1].toInt() and 0xFF)
        return (7 + (v % 32000)).toShort()
    }

    /**
     * The 6 weapon-skin rarity tiers are curated, not hashed -- this id
     * propagates directly into tradeup_recipes (millions of rows), so it
     * gets a fixed, zero-collision assignment instead of hash odds.
     * Keep this in sync with PostgresSeedService.rarityOrder and with
     * rarity_id_map's CASE expression in the SQL migration.
     */
    private val curatedRarityIds: Map<String, Short> = mapOf(
        "Consumer Grade" to 1,
        "Industrial Grade" to 2,
        "Mil-Spec Grade" to 3,
        "Restricted" to 4,
        "Classified" to 5,
        "Covert" to 6,
    )

    fun rarityId(name: String, externalId: String): Short =
        curatedRarityIds[name] ?: deterministicId16(externalId)
}

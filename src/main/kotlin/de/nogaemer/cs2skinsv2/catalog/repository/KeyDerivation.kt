package de.nogaemer.cs2skinsv2.catalog.repository

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

/**
 * Deterministic surrogate-key derivation for catalog entities and recipes.
 *
 * The catalog helpers (deterministicId / deterministicId16 / rarityId) MUST
 * stay byte-for-byte identical to the SQL functions of the same name in
 * 004_deterministic_catalog_ids.sql. recipeKey has no SQL-side equivalent --
 * it's computed entirely in Kotlin and inserted as a literal value, which is
 * the whole point of Phase 2b (no round trip needed to learn a recipe's id).
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

    /**
     * Deterministic recipe identity (Phase 2b) -- the first 16 bytes of a
     * recipe's canonical_hash (SHA-256 hex string), reinterpreted as a UUID.
     * Same logical recipe (same skins/counts/wear bucket) always produces
     * the same UUID, regardless of insert order or a crash-and-regenerate
     * cycle, and needs zero database round trip to compute.
     */
    fun recipeKey(canonicalHashHex: String): UUID {
        val mostSigBits = java.lang.Long.parseUnsignedLong(canonicalHashHex.substring(0, 16), 16)
        val leastSigBits = java.lang.Long.parseUnsignedLong(canonicalHashHex.substring(16, 32), 16)
        return UUID(mostSigBits, leastSigBits)
    }
}
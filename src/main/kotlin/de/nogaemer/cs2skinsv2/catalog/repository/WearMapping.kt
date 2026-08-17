package de.nogaemer.cs2skinsv2.catalog.repository

object WearMapping {
    fun fromDisplayName(name: String): String {
        return when (name.lowercase()) {
            "factory new" -> "factory_new"
            "minimal wear" -> "minimal_wear"
            "field-tested" -> "field_tested"
            "well-worn" -> "well_worn"
            "battle-scarred" -> "battle_scarred"
            else -> error("Unknown wear condition: $name")
        }
    }
}
package models

enum class CSWear(
    val id: String,
    val displayName: String,
    val min: Double,
    val generationMin: Double,
    val max: Double,
    val probability: Double,
) {
    FACTORY_NEW("factory_new", "Factory New", 0.00, 0.00, 0.07, 0.03),
    MINIMAL_WEAR("minimal_wear", "Minimal Wear", 0.07, 0.08, 0.15, 0.24),
    FIELD_TESTED("field_tested", "Field-Tested", 0.15, 0.16, 0.38, 0.33),
    WELL_WORN("well_worn", "Well-Worn", 0.38, 0.39, 0.45, 0.24),
    BATTLE_SCARRED("battle_scarred", "Battle-Scarred", 0.45, 0.46, 1.00, 0.16);

    companion object {
        fun floatToCSWear(float: Double): CSWear {
            return entries.find { it.min <= float && float <= it.max } ?: FACTORY_NEW
        }

        fun fromId(id: String): CSWear? {
            return entries.find { it.id == id }
        }
    }
}
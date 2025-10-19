enum class CSWear(
    val displayName: String,
    val min: Double,
    val generationMin: Double,
    val max: Double,
    val probability: Double,
) {
    FACTORY_NEW("Factory New", 0.00, 0.00, 0.07, 0.03),
    MINIMAL_WEAR("Minimal Wear", 0.07, 0.08, 0.15, 0.24),
    FIELD_TESTED("Field-Tested", 0.15, 0.16, 0.38, 0.33),
    WELL_WORN("Well-Worn", 0.38, 0.39, 0.45, 0.24),
    BATTLE_SCARRED("Battle-Scarred", 0.45, 0.46, 1.00, 0.16)
}
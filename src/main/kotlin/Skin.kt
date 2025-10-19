data class Skin(
    val name: String,
    val price: Double,
    val minFloatCap: Double,
    val maxFloatCap: Double,
    val floatCapDifference: Double = maxFloatCap - minFloatCap,
    val linearProbabilities: MutableMap<CSWear, MutableList<DropProbability.ProbabilityLinear>> = mutableMapOf()
)

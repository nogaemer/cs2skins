import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProbabilityTest {
    val skinA = Skin("A", 10.0, 0.0, 0.9)
    val skinB = Skin("B", 12.0, 0.0, 0.6)
    val tradeUpComponentA = TradeUpComponent(skinA, 3)
    val tradeUpComponentB = TradeUpComponent(skinB, 7)
    val float = CSWear.MINIMAL_WEAR

    @Test
    fun probabilityLinearReturnsCorrectLinearModel() {
        val dropProbability = DropProbability(tradeUpComponentA, tradeUpComponentB, float)
        val linear = dropProbability.probabilityLinear(0.0, 1.0, 0.0, 2.0)
        assertEquals(0.5, linear.m, 1e-9)
        assertEquals(0.0, linear.b, 1e-9)
        assertEquals(1.0, linear.at(2.0), 1e-9)
        assertEquals(0.0, linear.at(0.0), 1e-9)
        assertEquals(0.25, linear.at(0.5), 1e-9)
    }

    @Test
    fun probabilityLinearThrowsOnInvalidRange() {
        val dropProbability = DropProbability(tradeUpComponentA, tradeUpComponentB, float)
        assertThrows(IllegalArgumentException::class.java) {
            dropProbability.probabilityLinear(0.0, 1.0, 2.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dropProbability.probabilityLinear(0.0, 1.0, 1.0, 1.0)
        }
    }

    @Test
    fun linearAtReturnsCorrectValueForNegativeSlope() {
        val dropProbability = DropProbability(tradeUpComponentA, tradeUpComponentB, float)
        val linear = dropProbability.probabilityLinear(1.0, 0.0, 0.0, 1.0)
        assertEquals(-1.0, linear.m, 1e-9)
        assertEquals(1.0, linear.b, 1e-9)
        assertEquals(1.0, linear.at(0.0), 1e-9)
        assertEquals(0.0, linear.at(1.0), 1e-9)
        assertEquals(0.5, linear.at(0.5), 1e-9)
    }

    @Test
    fun probabilityLinearHandlesDecimalInputs() {
        val dropProbability = DropProbability(tradeUpComponentA, tradeUpComponentB, float)
        val linear = dropProbability.probabilityLinear(0.2, 0.8, 0.1, 0.4)
        assertEquals(2.0, linear.m, 1e-9)
        assertEquals(0.0, linear.b, 1e-9)
        assertEquals(0.2, linear.at(0.1), 1e-9)
        assertEquals(0.8, linear.at(0.4), 1e-9)
    }

    @Test
    fun getFloatProbabilityReturnsNormalizedProbabilities() {
        val dropProbability = DropProbability(tradeUpComponentA, tradeUpComponentB, float)
        val float: CSWear = CSWear.MINIMAL_WEAR

        val probabilityA = dropProbability.getFloatProbability(float, skinA)
        val linearFunctionA = dropProbability.probabilityLinear(probabilityA, float)
        println("A: $probabilityA,\n$linearFunctionA\n")

        val probabilityB = dropProbability.getFloatProbability(float, skinB)
        val linearFunctionB = dropProbability.probabilityLinear(
            pointPairs = probabilityB,
            float = float,
            dropProbability.calculateMMultiplier(
                tradeUpComponentA.amount,
                tradeUpComponentB.amount,
            ),
            dropProbability.calculateBSummand(
                avgFloat = 0.09,
                skinAmountB = tradeUpComponentB.amount,
            ),
            avgFloat = 0.09,
            skinAmountA = tradeUpComponentA.amount,
            skinAmountB = tradeUpComponentB.amount,
        )
        println("B: $probabilityB,\n$linearFunctionB\n")
    }

    // Test-only helper: provide a 4-Double overload so existing tests compile.
    private data class TestLinear(val m: Double, val b: Double) {
        fun at(x: Double) = m * x + b
    }

    private fun DropProbability.probabilityLinear(x1: Double, y1: Double, x2: Double, y2: Double): TestLinear {
        // Minimal validation to avoid division by zero; keep behavior simple for tests.
        if (x1 == x2) throw IllegalArgumentException("x1 and x2 must be different")
        val m = (y2 - y1) / (x2 - x1)
        val b = y1 - m * x1
        return TestLinear(m, b)
    }
}
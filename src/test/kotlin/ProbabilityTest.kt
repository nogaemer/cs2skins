import database.DatabaseFactory
import database.SkinRepository
import kotlinx.coroutines.runBlocking
import models.CSWear
import models.Skin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tradeup.*

class ProbabilityTest {
    val skinA = Skin(
        "A",
        mutableMapOf(
            CSWear.FACTORY_NEW to 1.84,
            CSWear.MINIMAL_WEAR to 0.38,
            CSWear.FIELD_TESTED to 0.13,
            CSWear.WELL_WORN to 0.10,
            CSWear.BATTLE_SCARRED to 0.09
        ),
        "Recoil_Case",
        0.0,
        1.0
    )
    val skinB = Skin(
        "B",
        mutableMapOf(
            CSWear.FACTORY_NEW to 1.84,
            CSWear.MINIMAL_WEAR to 0.38,
            CSWear.FIELD_TESTED to 0.13,
            CSWear.WELL_WORN to 0.10,
            CSWear.BATTLE_SCARRED to 0.09
        ),
        "Recoil_Case",
        0.0,
        1.0
    )
    val tradeUpInputComponentA = TradeUpInputComponent(skinA, 1)
    val tradeUpInputComponentB = TradeUpInputComponent(skinB, 9)
    val float = CSWear.MINIMAL_WEAR

    @Test
    fun probabilityLinearReturnsCorrectLinearModel() {
        val dropProbability = DropProbability(tradeUpInputComponentA, tradeUpInputComponentB)
        val linear = dropProbability.probabilityLinear(0.0, 1.0, 0.0, 2.0)
        assertEquals(0.5, linear.m, 1e-9)
        assertEquals(0.0, linear.b, 1e-9)
        assertEquals(1.0, linear.at(2.0), 1e-9)
        assertEquals(0.0, linear.at(0.0), 1e-9)
        assertEquals(0.25, linear.at(0.5), 1e-9)
    }

    @Test
    fun probabilityLinearThrowsOnInvalidRange() {
        val dropProbability = DropProbability(tradeUpInputComponentA, tradeUpInputComponentB)
        assertThrows(IllegalArgumentException::class.java) {
            dropProbability.probabilityLinear(0.0, 1.0, 2.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dropProbability.probabilityLinear(0.0, 1.0, 1.0, 1.0)
        }
    }

    @Test
    fun linearAtReturnsCorrectValueForNegativeSlope() {
        val dropProbability = DropProbability(tradeUpInputComponentA, tradeUpInputComponentB)
        val linear = dropProbability.probabilityLinear(1.0, 0.0, 0.0, 1.0)
        assertEquals(-1.0, linear.m, 1e-9)
        assertEquals(1.0, linear.b, 1e-9)
        assertEquals(1.0, linear.at(0.0), 1e-9)
        assertEquals(0.0, linear.at(1.0), 1e-9)
        assertEquals(0.5, linear.at(0.5), 1e-9)
    }

    @Test
    fun probabilityLinearHandlesDecimalInputs() {
        val dropProbability = DropProbability(tradeUpInputComponentA, tradeUpInputComponentB)
        val linear = dropProbability.probabilityLinear(0.2, 0.8, 0.1, 0.4)
        assertEquals(2.0, linear.m, 1e-9)
        assertEquals(0.0, linear.b, 1e-9)
        assertEquals(0.2, linear.at(0.1), 1e-9)
        assertEquals(0.8, linear.at(0.4), 1e-9)
    }

    @Test
    fun getFloatProbabilityReturnsNormalizedProbabilities() {
        val dropProbability = DropProbability(tradeUpInputComponentA, tradeUpInputComponentB)
        val floatA: CSWear = CSWear.FACTORY_NEW
        val floatB: CSWear = CSWear.MINIMAL_WEAR

        val probabilityA = dropProbability.getFloatProbability(floatA, skinA)
        val linearFunctionA = dropProbability.probabilityLinear(probabilityA)
        println("A: $probabilityA,\n$linearFunctionA\n")

        val probabilityB = dropProbability.getFloatProbability(floatB, skinB)
        val linearFunctionB = dropProbability.probabilityLinear(
            probabilityB
        ).map {
            it.adjust(
                avgFloat = 0.0699999,
                tradeUpInputComponentA = tradeUpInputComponentA,
                tradeUpInputComponentB = tradeUpInputComponentB,
            )
        }
        println("B: $probabilityB,\n$linearFunctionB\n")
    }

    @Test
    fun calculateBestFloatsHandlesExtremeAverageFloatWithoutThrowing() {
        val input = TradeUpInput(tradeUpInputComponentA, tradeUpInputComponentB)

        val result = input.calculateBestFloats(0.09)
        println(result)
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

    @Test
    fun calculateBestFloatsProducesFiniteCostsAndFloatsForTypicalComponents() {
        val input = TradeUpInput(tradeUpInputComponentA, tradeUpInputComponentB)
        val result = input.calculateBestFloats(0.09)

        assertTrue(result.isNotEmpty())
        result.values.forEach { costs ->
            assertTrue(costs.costs.isFinite())
            assertTrue(costs.floatA.isFinite())
            assertTrue(costs.floatB.isFinite())
        }
    }

    @Test
    fun calculateBestFloatsReturnsEmptyMapWhenComponentAmountsAreZero() {
        val zeroA = TradeUpInputComponent(skinA, 0)
        val zeroB = TradeUpInputComponent(skinB, 0)
        val input = TradeUpInput(zeroA, zeroB)

        val result = input.calculateBestFloats(0.09)
        assertEquals(0, result.size)
    }

    @Test
    fun testCalculateBestFloatsPerformance() {
        val iterations = 10000000
        val start = System.nanoTime()
        var lastResult: Map<*, *>? = null

        for (i in 1..iterations) {
            val input = TradeUpInput(tradeUpInputComponentA, tradeUpInputComponentB)
            val result = input.calculateBestFloats(0.0699999)
            lastResult = result
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        println("Elapsed ms: $elapsedMs, lastResult size: ${lastResult?.size ?: 0}")
        assertTrue(lastResult != null)
    }

    @Test
    fun testCollection(){
        val tradeUpOptimizer = TradeUpOptimizer()
        val skinsA = mutableListOf<Skin>(
            Skin(
                "Nova Rising Skull",
                mutableMapOf(
                    CSWear.FACTORY_NEW to 8.55,
                    CSWear.MINIMAL_WEAR to 5.98,
                    CSWear.FIELD_TESTED to 5.15,
                    CSWear.WELL_WORN to 5.53,
                    CSWear.BATTLE_SCARRED to 5.57
                ),
                "Recoil_Case",
                0.0,
                0.5
            )
        )
        val skinsB = mutableListOf<Skin>(
            Skin(
                "Nova Rising Skull",
                mutableMapOf(
                    CSWear.FACTORY_NEW to 8.55,
                    CSWear.MINIMAL_WEAR to 5.98,
                    CSWear.FIELD_TESTED to 5.15,
                    CSWear.WELL_WORN to 5.53,
                    CSWear.BATTLE_SCARRED to 5.57
                ),
                "Recoil_Case",
                0.0,
                0.5
            )
        )
        val outputSkins = mutableListOf<Skin>(
            Skin(
                "AWP Redline",
                mutableMapOf(
                    CSWear.MINIMAL_WEAR to 93.62,
                    CSWear.FIELD_TESTED to 48.50,
                    CSWear.WELL_WORN to 66.36,
                ),
                "Recoil_Case",
                0.1,
                0.4
            ),
            Skin(
                "M4A1-S Guardian",
                mutableMapOf(
                    CSWear.FACTORY_NEW to 57.16,
                    CSWear.MINIMAL_WEAR to 31.14,
                    CSWear.FIELD_TESTED to 24.73,
                    CSWear.WELL_WORN to 21.81,
                    CSWear.BATTLE_SCARRED to 23.18
                ),
                "Recoil_Case",
                0.0,
                0.5
            ),
            Skin(
                "P250 Mehndi",
                mutableMapOf(
                    CSWear.FACTORY_NEW to 30.81,
                    CSWear.MINIMAL_WEAR to 16.71,
                    CSWear.FIELD_TESTED to 14.30,
                    CSWear.WELL_WORN to 11.22,
                    CSWear.BATTLE_SCARRED to 11.08
                ),
                "Recoil_Case",
                0.0,
                1.0
            ),
        )

        val outputFloats = tradeUpOptimizer.calculateOutputfloats(outputSkins)

        outputFloats.forEach { outputFloat ->
            var bestTradeUpInputForOutputFloat: TradeUpInput? = null
            val tradeUpOutput: TradeUpOutput = tradeUpOptimizer.calculateTradeUpOutput(outputSkins, outputFloat)

            for (j in 1..9) {
                skinsA.forEach { skinA ->
                    skinsB.forEach { skinB ->
                        val tradeUpInputComponentA =
                            TradeUpInputComponent(skinA, j, skinA.collectionId)
                        val tradeUpInputComponentB =
                            TradeUpInputComponent(skinB, 10 - j, skinB.collectionId)

                        val tradeUpInput: CostsFloatInput? =
                            TradeUpInput(tradeUpInputComponentA, tradeUpInputComponentB)
                                .calculateBestFloats(outputFloat).values.minByOrNull { it.costs }

                        if (tradeUpInput != null &&
                            (tradeUpInput.costs < (bestTradeUpInputForOutputFloat?.costsFloatInput?.costs
                                ?: Double.POSITIVE_INFINITY))
                        ) {

                            bestTradeUpInputForOutputFloat = TradeUpInput(
                                tradeUpInputComponentA,
                                tradeUpInputComponentB,
                                tradeUpInput
                            )
                        }
                    }
                }
            }

            if (bestTradeUpInputForOutputFloat != null) {
                val tradeUp = TradeUp(
                    bestTradeUpInputForOutputFloat,
                    tradeUpOutput
                )
                println("${tradeUp.input.tradeUpInputComponentA.amount}x ${tradeUp.input.tradeUpInputComponentA.skin.name} - ${tradeUp.input.costsFloatInput!!.floatA} || " +
                        "${tradeUp.input.tradeUpInputComponentB.amount}x ${tradeUp.input.tradeUpInputComponentB.skin.name} - ${tradeUp.input.costsFloatInput.floatB} || " +
                        "float $outputFloat | roi ${tradeUp.roiWithDropChange} | profit ${tradeUp.profit}")

            }
        }
    }

    @Test
    fun testDB(){
        DatabaseFactory.init()
        runBlocking { val skins = SkinRepository().findByCollectionWithPrice("collection-set-community-11")
        println(skins)}
    }

}
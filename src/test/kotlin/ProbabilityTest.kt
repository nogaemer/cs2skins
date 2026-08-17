import models.CSWear
import models.Skin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tradeup.DropProbability
import tradeup.ProbabilityLinear
import tradeup.TradeUpInput
import tradeup.TradeUpInputComponent

class ProbabilityTest {

    private val skinA = Skin(
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
        0.80339
    )
    private val skinB = Skin(
        "B",
        mutableMapOf(
            CSWear.FACTORY_NEW to 1.84,
            CSWear.MINIMAL_WEAR to 0.38,
            CSWear.FIELD_TESTED to 0.13,
            CSWear.WELL_WORN to 0.10,
            CSWear.BATTLE_SCARRED to 0.09
        ),
        "Recoil_Case",
        0.06,
        0.8
    )
    private val componentA = TradeUpInputComponent(skinA, 1)
    private val componentB = TradeUpInputComponent(skinB, 9)

    // --- DropProbability.probabilityLinear(FloatProbabilityPointPair) ---
    // These call the REAL production method against a real
    // FloatProbabilityPointPair -- the old versions of these tests called a
    // fake local 4-Double helper this file defined for itself, which didn't
    // match the real method's signature and produced results that never
    // actually satisfied their own assertions.

    @Test
    fun probabilityLinearComputesCorrectLineThroughTwoPoints() {
        val dropProbability = DropProbability(componentA, componentB)
        val pointPair = DropProbability.FloatProbabilityPointPair(
            DropProbability.FloatProbabilityPoint(0.0, 0.0),
            DropProbability.FloatProbabilityPoint(2.0, 1.0),
            floatDifference = 0.0
        )
        val linear = dropProbability.probabilityLinear(pointPair)
        assertEquals(0.5, linear.m, 1e-9)
        assertEquals(0.0, linear.b, 1e-9)
        assertEquals(1.0, linear.at(2.0), 1e-9)
        assertEquals(0.0, linear.at(0.0), 1e-9)
        assertEquals(0.25, linear.at(0.5), 1e-9)
    }

    @Test
    fun probabilityLinearThrowsWhenUpperFloatNotGreaterThanLowerFloat() {
        val dropProbability = DropProbability(componentA, componentB)
        assertThrows(IllegalArgumentException::class.java) {
            dropProbability.probabilityLinear(
                DropProbability.FloatProbabilityPointPair(
                    DropProbability.FloatProbabilityPoint(1.0, 0.0),
                    DropProbability.FloatProbabilityPoint(0.5, 1.0),
                    floatDifference = 0.0
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            dropProbability.probabilityLinear(
                DropProbability.FloatProbabilityPointPair(
                    DropProbability.FloatProbabilityPoint(1.0, 0.0),
                    DropProbability.FloatProbabilityPoint(1.0, 1.0),
                    floatDifference = 0.0
                )
            )
        }
    }

    @Test
    fun probabilityLinearHandlesNegativeSlope() {
        val dropProbability = DropProbability(componentA, componentB)
        val pointPair = DropProbability.FloatProbabilityPointPair(
            DropProbability.FloatProbabilityPoint(0.0, 1.0),
            DropProbability.FloatProbabilityPoint(1.0, 0.0),
            floatDifference = 0.0
        )
        val linear = dropProbability.probabilityLinear(pointPair)
        assertEquals(-1.0, linear.m, 1e-9)
        assertEquals(1.0, linear.b, 1e-9)
        assertEquals(1.0, linear.at(0.0), 1e-9)
        assertEquals(0.0, linear.at(1.0), 1e-9)
        assertEquals(0.5, linear.at(0.5), 1e-9)
    }

    @Test
    fun probabilityLinearHandlesDecimalInputs() {
        val dropProbability = DropProbability(componentA, componentB)
        val pointPair = DropProbability.FloatProbabilityPointPair(
            DropProbability.FloatProbabilityPoint(0.1, 0.2),
            DropProbability.FloatProbabilityPoint(0.4, 0.8),
            floatDifference = 0.0
        )
        val linear = dropProbability.probabilityLinear(pointPair)
        assertEquals(2.0, linear.m, 1e-9)
        assertEquals(0.0, linear.b, 1e-9)
        assertEquals(0.2, linear.at(0.1), 1e-9)
        assertEquals(0.8, linear.at(0.4), 1e-9)
    }

    // --- DropProbability.getFloatProbability ---
    // The old version of this test only printed output with zero
    // assertions -- it exercised real production code but verified
    // nothing. This version keeps the real calls and adds actual checks:
    // getFloatProbability normalizes its returned pairs so the final
    // upper.probability sums to 1.0 across the whole list.

    @Test
    fun getFloatProbabilityReturnsNormalizedProbabilitiesSummingToOne() {
        val dropProbability = DropProbability(componentA, componentB)
        val pointPairs = dropProbability.getFloatProbability(CSWear.MINIMAL_WEAR, skinB)

        assertTrue(pointPairs.isNotEmpty())
        assertEquals(1.0, pointPairs.last().upper.probability, 1e-6)

        pointPairs.forEach { pair ->
            assertTrue(pair.lower.probability.isFinite())
            assertTrue(pair.upper.probability.isFinite())
            assertTrue(pair.upper.float > pair.lower.float)
        }
    }

    @Test
    fun buildDropProbabilityListMapBProducesEntriesForAllWears() {
        val dropProbability = DropProbability(componentA, componentB)
        val avgFloat = 0.1166665

        val dropProbabilityListMapB: MutableMap<CSWear, List<ProbabilityLinear>> =
            CSWear.entries.associateWith { wearSkinB ->
                dropProbability.getFloatProbability(wearSkinB, componentB.skin)
                    .map { pointPair -> dropProbability.probabilityLinear(pointPair) }
                    .map { it.adjust(avgFloat, componentA, componentB) }
                    .toList()
            }.toMutableMap()

        assertEquals(CSWear.entries.size, dropProbabilityListMapB.size)
        CSWear.entries.forEach { wear ->
            assertTrue(dropProbabilityListMapB.containsKey(wear))
            dropProbabilityListMapB[wear]!!.forEach { linear ->
                assertTrue(linear.m.isFinite())
                assertTrue(linear.b.isFinite())
            }
        }
    }

    // --- TradeUpInput.calculateBestFloats ---
    // Unaffected by the signature issue above -- these already called the
    // real method correctly.

    @Test
    fun calculateBestFloatsHandlesExtremeAverageFloatWithoutThrowing() {
        val input = TradeUpInput(componentA, componentB)
        val result = input.calculateBestFloats(0.09)
        assertNotNull(result)
    }

    @Test
    fun calculateBestFloatsProducesFiniteCostsAndFloatsForTypicalComponents() {
        val input = TradeUpInput(componentA, componentB)
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
}
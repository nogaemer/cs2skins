import com.nogaemer.cs2skins.Application
import database.SkinDTO
import database.SkinRepository
import kotlinx.coroutines.runBlocking
import models.CSWear
import models.Skin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tradeup.*

/**
 * Integration test for the trade-up price calculation algorithm.
 *
 * HOW TO USE
 * ──────────
 * 1. Make sure the local DB is running and populated with skin + price data.
 * 2. Set [SKIN_A_ID], [SKIN_B_ID], [AMOUNT_A], and [OUTPUT_FLOAT] below.
 * 3. Run `print trade-up metrics for manual inspection` first — it dumps every
 *    computed number to the console so you can inspect the result.
 * 4. Once happy, fill in the hardened assertions at the bottom of
 *    `tradeup metrics match expected values` to lock in regression protection.
 *
 * The output-skin pool is resolved automatically from the DB: the test fetches
 * every skin in the same collection(s) and rarity tier above the input skins,
 * exactly as the production pipeline does.
 */
@SpringBootTest(classes = [Application::class])
class TradeUpCalculationTest {

    // ──────────────────────────────────────────────────────────────────────────
    // ► CONFIGURE YOUR TRADE-UP HERE
    // ──────────────────────────────────────────────────────────────────────────

    /** DB skin_id of the first input skin. */
    private val SKIN_A_ID = "skin-27a11235665e"      // ← replace with a real skin_id from your DB

    /** DB skin_id of the second input skin. */
    private val SKIN_B_ID = "skin-20f06d331186"      // ← replace with a real skin_id from your DB

    /** How many of skin A to use (1–9); skin B receives 10 − AMOUNT_A. */
    private val AMOUNT_A = 6

    private val AMOUNT_B = 10 - AMOUNT_A

    /**
     * Average normalised input float that produces the desired output float.
     * If you know the output float you want, convert it first:
     *   inputFloat = (outputFloat − minFloat) / (maxFloat − minFloat)
     * for the output skin's float caps.
     */
    private val OUTPUT_FLOAT = 0.069999999999

    // ──────────────────────────────────────────────────────────────────────────
    // ► INTERNALS — no need to edit below this line
    // ──────────────────────────────────────────────────────────────────────────

    private val skinRepository = SkinRepository()

    // ── DB helpers ────────────────────────────────────────────────────────────

    /** Fetches a skin with prices from the DB and converts it to a [Skin]. */
    private suspend fun fetchSkin(skinId: String): Skin {
        val dto = skinRepository.findById(skinId)
            ?: error("Skin '$skinId' not found in DB — is the database populated?")
        return skinDtoToSkin(dto)
    }

    /**
     * Fetches all skins for [collectionId] that belong to [rarityName], priced,
     * and converts them to [Skin] objects suitable for the output pool.
     */
    private suspend fun fetchOutputSkins(
        collectionAId: String,
        collectionBId: String,
        outputRarityName: String
    ): MutableList<Skin> {
        val skinsA = skinRepository.findByCollectionWithPrice(collectionAId, false)
        val skinsB = if (collectionBId == collectionAId) emptyList()
                     else skinRepository.findByCollectionWithPrice(collectionBId, false)

        return (skinsA + skinsB)
            .filter { it.rarity.name == outputRarityName }
            .map { skinDtoToSkin(it) }
            .distinctBy { it.skinId }
            .toMutableList()
    }

    /** Mirrors [TradeUpOptimizer.skinDtoToSkin] — converts DB DTO to model. */
    private fun skinDtoToSkin(dto: SkinDTO): Skin {
        val priceMap: MutableMap<CSWear, Double> =
            dto.price.mapValues { (_, sp) -> sp.price.toDouble() }.toMutableMap()
        return Skin(
            skinId       = dto.skinId,
            name         = dto.name,
            collectionId = dto.collectionId ?: "",
            price        = priceMap,
            minFloatCap  = dto.minFloat,
            maxFloatCap  = dto.maxFloat
        )
    }

    /** Looks up the rarity tier directly above [rarityName] in the CS2 order. */
    private fun nextRarity(rarityName: String): String {
        val order = listOf(
            "Consumer Grade", "Industrial Grade", "Mil-Spec Grade",
            "Restricted", "Classified", "Covert"
        )
        val idx = order.indexOf(rarityName)
        require(idx >= 0)          { "Unknown rarity '$rarityName'" }
        require(idx + 1 < order.size) { "No rarity above '$rarityName' (already Covert)" }
        return order[idx + 1]
    }

    /** Converts a normalised input float to the actual output-skin float. */
    private fun inputToOutputFloat(inputFloat: Double, minFloat: Double, maxFloat: Double) =
        inputFloat * (maxFloat - minFloat) + minFloat

    /** Attaches computed output floats to each output-pool skin. */
    private fun buildOutput(skins: MutableList<Skin>, avgFloat: Double): TradeUpOutput {
        val withFloats = skins.map { s ->
            s.copy(float = inputToOutputFloat(avgFloat, s.minFloatCap, s.maxFloatCap))
        }.toMutableList()
        return TradeUpOutput(
            skins = withFloats,
            costs = withFloats.sumOf { s -> s.price[CSWear.floatToCSWear(s.float!!)] ?: 0.0 }
        )
    }

    /**
     * Full end-to-end calculation:
     *  1. Fetches both input skins (with live prices) from the DB.
     *  2. Resolves the output-pool skins from the next rarity tier.
     *  3. Calls [TradeUpInput.calculateBestFloats] to find the optimal input floats.
     *  4. Returns the resulting [TradeUp], or `null` if no valid combination exists.
     */
    private suspend fun calculate(
        skinAId: String,
        skinBId: String,
        amountA: Int,
        amountB: Int,
        outputFloat: Double
    ): Pair<TradeUp, Pair<Skin, Skin>>? {
        require(amountA + amountB == 10) { "amountA + amountB must equal 10, got $amountA + $amountB" }
        require(amountA in 1..9)         { "amountA must be 1–9, got $amountA" }

        val skinA = fetchSkin(skinAId)
        val skinB = fetchSkin(skinBId)

        // Resolve the rarity tier of skin A (both input skins must share the same rarity)
        val dtoA = skinRepository.findById(skinAId)!!
        val inputRarityName  = dtoA.rarity.name
        val outputRarityName = nextRarity(inputRarityName)

        val outputSkins = fetchOutputSkins(skinA.collectionId, skinB.collectionId, outputRarityName)
        check(outputSkins.isNotEmpty()) {
            "No output skins found for rarity '$outputRarityName' in collections " +
            "'${skinA.collectionId}' / '${skinB.collectionId}'"
        }

        val componentA = TradeUpInputComponent(skinA, amountA, skinA.collectionId)
        val componentB = TradeUpInputComponent(skinB, amountB, skinB.collectionId)
        val output     = buildOutput(outputSkins, outputFloat)

        val bestFloat = TradeUpInput(componentA, componentB)
            .calculateBestFloats(outputFloat)
            .values
            .minByOrNull { it.costsWithDropChange }
            ?: return null

        return TradeUp(
            input  = TradeUpInput(componentA, componentB, bestFloat),
            output = output
        ) to (skinA to skinB)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ► TESTS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Prints a full human-readable summary of the trade-up to the test console.
     * Run this first to inspect numbers before writing hardened assertions.
     */
    @Test
    fun `print trade-up metrics for manual inspection`() = runBlocking {
        val result = calculate(SKIN_A_ID, SKIN_B_ID, AMOUNT_A, AMOUNT_B, OUTPUT_FLOAT)
        if (result == null) {
            println("⚠  No valid float combination found — check skin prices and float caps.")
            return@runBlocking
        }
        val (tradeUp, skins) = result
        val (skinA, skinB)   = skins
        val cfi = tradeUp.input.costsFloatInput!!

        println("\n════════════════════════════════════════════════════════════════")
        println(" Trade-up calculation result  (from live DB)")
        println("════════════════════════════════════════════════════════════════")
        println(" Skin A ID : $SKIN_A_ID  →  ${skinA.name}")
        println("   minFloat=${skinA.minFloatCap}  maxFloat=${skinA.maxFloatCap}")
        println("   prices  : ${skinA.price.entries.sortedBy { it.key }.joinToString(" | ") {
            "${it.key.displayName}=%.2f".format(it.value)
        }}")
        println(" Skin B ID : $SKIN_B_ID  →  ${skinB.name}")
        println("   minFloat=${skinB.minFloatCap}  maxFloat=${skinB.maxFloatCap}")
        println("   prices  : ${skinB.price.entries.sortedBy { it.key }.joinToString(" | ") {
            "${it.key.displayName}=%.2f".format(it.value)
        }}")
        println(" Amount    : ${AMOUNT_A}× A  +  ${AMOUNT_B}× B  (target outputFloat=$OUTPUT_FLOAT)")
        println("────────────────────────────────────────────────────────────────")
        println(" Optimal input floats:")
        println("   floatA = ${"%.6f".format(cfi.floatA)}  → ${CSWear.floatToCSWear(cfi.floatA).displayName}")
        println("   floatB = ${"%.6f".format(cfi.floatB)}  → ${CSWear.floatToCSWear(cfi.floatB).displayName}")
        println("────────────────────────────────────────────────────────────────")
        println(" Output pool  (${tradeUp.output.skins.size} skins):")
        tradeUp.output.skins.forEach { s ->
            val f    = s.float ?: Double.NaN
            val wear = CSWear.floatToCSWear(f)
            println("   • %-40s float=%.4f  %-14s  price=%.2f".format(
                s.name, f, "(${wear.displayName})", s.price[wear] ?: 0.0))
        }
        println("────────────────────────────────────────────────────────────────")
        println(" inputCost  (no drop-change)   : ${"%.4f".format(tradeUp.inputCost)}")
        println(" inputCost  (with drop-change) : ${"%.4f".format(tradeUp.inputCostWithDropChange)}")
        println(" expectedReturn                : ${"%.4f".format(tradeUp.expectedReturn)}")
        println(" profit     (no drop-change)   : ${"%.4f".format(tradeUp.profit)}")
        println(" profit     (with drop-change) : ${"%.4f".format(tradeUp.profitWithDropChange)}")
        println(" ROI        (no drop-change)   : ${"%.4f".format(tradeUp.roi)}")
        println(" ROI        (with drop-change) : ${"%.4f".format(tradeUp.roiWithDropChange)}")
        println(" profitChance                  : ${"%.2f".format(tradeUp.profitChance * 100)}%")
        println("════════════════════════════════════════════════════════════════\n")
    }

    /**
     * Structural correctness assertions — verified against live DB prices.
     * These pass as long as the algorithm is internally consistent; they do NOT
     * depend on any specific dollar amounts (use hardened assertions for that).
     */
    @Test
    fun `tradeup structural assertions pass with live prices`() = runBlocking {
        val result = calculate(SKIN_A_ID, SKIN_B_ID, AMOUNT_A, AMOUNT_B, OUTPUT_FLOAT)
        assertNotNull(result, "No valid trade-up found — is the DB populated with prices?")
        val (tradeUp, skins) = result!!
        val (skinA, skinB)   = skins
        val cfi = tradeUp.input.costsFloatInput!!

        // ── Input floats lie within each skin's valid float range ─────────────
        assertTrue(cfi.floatA in skinA.minFloatCap..skinA.maxFloatCap,
            "floatA=${cfi.floatA} outside [${skinA.minFloatCap}, ${skinA.maxFloatCap}]")
        assertTrue(cfi.floatB in skinB.minFloatCap..skinB.maxFloatCap,
            "floatB=${cfi.floatB} outside [${skinB.minFloatCap}, ${skinB.maxFloatCap}]")

        // ── Weighted average of normalised floats reproduces OUTPUT_FLOAT ─────
        val normA = (cfi.floatA - skinA.minFloatCap) / skinA.floatCapDifference
        val normB = (cfi.floatB - skinB.minFloatCap) / skinB.floatCapDifference
        val reconstructed = (AMOUNT_A * normA + AMOUNT_B * normB) / 10.0
        assertEquals(OUTPUT_FLOAT, reconstructed, 1e-3,
            "Reconstructed avg float $reconstructed ≠ target $OUTPUT_FLOAT")

        // ── Costs are positive and finite ─────────────────────────────────────
        assertTrue(tradeUp.inputCost.isFinite() && tradeUp.inputCost > 0.0,
            "inputCost=${tradeUp.inputCost}")
        assertTrue(tradeUp.inputCostWithDropChange.isFinite() && tradeUp.inputCostWithDropChange > 0.0,
            "inputCostWithDropChange=${tradeUp.inputCostWithDropChange}")
        assertTrue(tradeUp.inputCostWithDropChange >= tradeUp.inputCost,
            "Drop-change cost should be >= base cost")

        // ── P&L identity: profit = expectedReturn − inputCost ─────────────────
        assertEquals(
            tradeUp.expectedReturn - tradeUp.inputCostWithDropChange,
            tradeUp.profitWithDropChange, 1e-9,
            "profitWithDropChange identity broken"
        )
        assertEquals(
            tradeUp.expectedReturn / tradeUp.inputCostWithDropChange,
            tradeUp.roiWithDropChange, 1e-9,
            "roiWithDropChange identity broken"
        )

        // ── Expected return is positive and finite ────────────────────────────
        assertTrue(tradeUp.expectedReturn.isFinite() && tradeUp.expectedReturn > 0.0,
            "expectedReturn=${tradeUp.expectedReturn}")

        // ── Profit chance in [0, 1] ───────────────────────────────────────────
        assertTrue(tradeUp.profitChance in 0.0..1.0,
            "profitChance=${tradeUp.profitChance}")
    }

    /**
     * Hardened regression test.
     * After running the print test above, paste the exact numbers here.
     * Tolerances: ±0.01 for prices/costs, ±0.001 for ratios.
     *
     * Example — uncomment and fill in after first run:
     *
     *   assertEquals(42.50,  tradeUp.inputCostWithDropChange, 0.01)
     *   assertEquals(25.80,  tradeUp.expectedReturn,          0.01)
     *   assertEquals(-16.70, tradeUp.profitWithDropChange,    0.01)
     *   assertEquals(0.607,  tradeUp.roiWithDropChange,       0.001)
     *   assertEquals(0.0,    tradeUp.profitChance,            0.001)
     */
    @Test
    fun `tradeup metrics match expected values`() = runBlocking {
        val result = calculate(SKIN_A_ID, SKIN_B_ID, AMOUNT_A, AMOUNT_B, OUTPUT_FLOAT)
        assertNotNull(result, "No trade-up found — run the print test first")
        val tradeUp = result!!.first

        // ► Paste hardened assertions here after inspecting the print output:
        // assertEquals(<inputCostWithDropChange>, tradeUp.inputCostWithDropChange, 0.01)
        // assertEquals(<expectedReturn>,          tradeUp.expectedReturn,          0.01)
        // assertEquals(<profitWithDropChange>,     tradeUp.profitWithDropChange,    0.01)
        // assertEquals(<roiWithDropChange>,        tradeUp.roiWithDropChange,       0.001)
        // assertEquals(<profitChance>,             tradeUp.profitChance,            0.001)
    }

    /** Guards against accidentally passing an invalid amount split. */
    @Test
    fun `calculate throws when amounts do not sum to 10`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { calculate(SKIN_A_ID, SKIN_B_ID, 3, 3, OUTPUT_FLOAT) }
        }
    }
}


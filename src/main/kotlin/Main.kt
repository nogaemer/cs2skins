//package de.nogaemer
//
//
//
//// Extended usage example with realistic CS2 trade-up scenarios
//// Based on actual market data and popular trade-up routes
//
//fun demonstrateRealWorldTradeups() {
//    val optimizer = CS2TradeupOptimizer()
//
//    println("=== REAL WORLD CS2 TRADE-UP EXAMPLES ===\n")
//
//    // Example 1: Popular Dragon Lore trade-up route
//    println("1. AWP DRAGON LORE TRADE-UP")
//    println("=".repeat(40))
//
//    val cobblestoneInputs = listOf(
//        Skin(0.06, 0.80, 15.50, "M4A1-S | Master Piece"), // Cobblestone collection
//        Skin(0.00, 0.75, 12.30, "P2000 | Chainmail"),     // Cobblestone collection
//    )
//
//    val dragonLore = Skin(0.00, 0.07, 4500.0, "AWP | Dragon Lore")
//    val alternateCobble = listOf(
//        Skin(0.06, 0.80, 850.0, "AK-47 | Jaguar")
//    )
//
//    val comp1_dl = TradeupComponent(7, cobblestoneInputs[0])
//    val comp2_dl = TradeupComponent(3, cobblestoneInputs[1])
//    val avgFloat_dl = 0.035 // Very low float for FN chance
//
//    val dlAnalysis = optimizer.calculateBestFloats(
//        comp1_dl, comp2_dl, avgFloat_dl, dragonLore, alternateCobble
//    )
//
//    println("Investment: ${'$'}%.2f".format(dlAnalysis.inputCost))
//    println("Expected Return: ${'$'}%.2f".format(dlAnalysis.expectedValue))
//    println("Potential Profit: ${'$'}%.2f (%.1f%%)".format(dlAnalysis.profit, dlAnalysis.profitability))
//    println(
//        "Risk Assessment: %.1f%% chance of loss due to market fees".format(
//            (dlAnalysis.failureCost / dlAnalysis.inputCost) * 100
//        )
//    )
//    println()
//
//    // Example 2: Budget-friendly Redline trade-up
//    println("2. AK-47 REDLINE BUDGET TRADE-UP")
//    println("=".repeat(40))
//
//    val budgetInputs = listOf(
//        Skin(0.15, 0.80, 0.45, "P250 | Hive"),
//        Skin(0.06, 0.70, 0.85, "Five-SeveN | Case Hardened")
//    )
//
//    val redlineTarget = Skin(0.10, 0.70, 28.50, "AK-47 | Redline")
//    val redlineAlts = listOf(
//        Skin(0.08, 0.75, 12.40, "SSG 08 | Blood in the Water"),
//        Skin(0.00, 0.65, 8.90, "Desert Eagle | Hypnotic")
//    )
//
//    val comp1_rl = TradeupComponent(6, budgetInputs[0])
//    val comp2_rl = TradeupComponent(4, budgetInputs[1])
//    val avgFloat_rl = 0.08 // Target MW condition
//
//    val rlAnalysis = optimizer.calculateBestFloats(
//        comp1_rl, comp2_rl, avgFloat_rl, redlineTarget, redlineAlts
//    )
//
//    println("Investment: ${'$'}%.2f".format(rlAnalysis.inputCost))
//    println("Expected Return: ${'$'}%.2f".format(rlAnalysis.expectedValue))
//    println("Potential Profit: ${'$'}%.2f (%.1f%%)".format(rlAnalysis.profit, rlAnalysis.profitability))
//    println(
//        "Average Input Float: %.4f (targets %s condition)".format(
//            rlAnalysis.averageInputFloat,
//            redlineTarget.getWearGroup(avgFloat_rl)
//        )
//    )
//    println()
//
//    // Example 3: Multi-collection probability optimization
//    println("3. MULTI-COLLECTION PROBABILITY ANALYSIS")
//    println("=".repeat(40))
//
//    val collections = listOf("Dust2", "Mirage", "Inferno", "Cache", "Vertigo")
//    val probFunctions = optimizer.createProbabilityFunctions(collections, WearGroup.FACTORY_NEW)
//
//    println("Linear probability functions for Factory New outcomes:")
//    val testFloats = listOf(0.001, 0.01, 0.03, 0.05, 0.069)
//
//    for (float in testFloats) {
//        println("\nFloat %.3f:".format(float))
//        probFunctions.forEach { (collection, function) ->
//            val prob = function(float)
//            println("  %s: %.6f".format(collection, prob))
//        }
//    }
//
//    println()
//
//    // Example 4: Cost optimization algorithm
//    println("4. INPUT COST OPTIMIZATION")
//    println("=".repeat(40))
//
//    val marketSkins = listOf(
//        Skin(0.06, 0.80, 0.25, "P250 | Sand Dune FT"),
//        Skin(0.00, 0.70, 0.55, "P250 | Sand Dune MW"),
//        Skin(0.00, 0.07, 0.95, "P250 | Sand Dune FN"),
//        Skin(0.15, 0.75, 1.25, "Galil AR | Sandstorm FT"),
//        Skin(0.07, 0.65, 2.15, "Galil AR | Sandstorm MW"),
//        Skin(0.00, 0.50, 4.50, "AK-47 | Safari Mesh MW")
//    )
//
//    val targetSkin = Skin(0.00, 0.40, 75.0, "AK-47 | Case Hardened Blue Gem")
//
//    println("Finding optimal combinations for budget: ${'$'}15.00")
//    val optimized = optimizer.optimizeInputCombination(marketSkins, targetSkin, 15.0)
//
//    println("\nTop 5 most profitable combinations:")
//    optimized.take(5).forEachIndexed { index, (comp1, comp2) ->
//        val avgFloat = (comp1.amount * comp1.skin.minFloat + comp2.amount * comp2.skin.minFloat) / 10.0
//        val analysis = optimizer.calculateBestFloats(comp1, comp2, avgFloat, targetSkin)
//
//        println("\n${index + 1}. ${comp1.amount}x ${comp1.skin.name} + ${comp2.amount}x ${comp2.skin.name}")
//        println("   Total Cost: ${'$'}%.2f".format(analysis.inputCost))
//        println("   Expected Value: ${'$'}%.2f".format(analysis.expectedValue))
//        println("   ROI: %.1f%%".format(analysis.profitability))
//        println(
//            "   Avg Float: %.4f → Expected output: %.4f".format(
//                avgFloat,
//                optimizer.calculateOutputFloat(targetSkin, avgFloat)
//            )
//        )
//    }
//
//    println("=".repeat(40))
//    println("OPTIMIZATION COMPLETE - Choose combination with highest ROI")
//    println("Remember: Lower input floats = better output condition = higher value!")
//    println("Factor in the 13% Steam market fee for unwanted outcomes.")
//    println("=".repeat(40))
//}
//
//// Helper extension for output formatting
//private fun CS2TradeupOptimizer.calculateOutputFloat(skin: Skin, avgInput: Double): Double {
//    return (skin.maxFloat - skin.minFloat) * avgInput + skin.minFloat
//}
//
//// Call the demonstration
//fun main() {
//    demonstrateRealWorldTradeups()
//}

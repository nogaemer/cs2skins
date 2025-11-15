import database.DatabaseFactory
import tradeup.TradeUpOptimizer

suspend fun main() {
    // Initialize database
    DatabaseFactory.init()

//    val seedDB = SeedDB()
//    seedDB.seedCollections()
//    seedDB.seedSkins()

    TradeUpOptimizer().optimizeAll()

}

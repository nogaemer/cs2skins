import database.DatabaseFactory
import tradeup.TradeUpOptimizer

/**
 * LEGACY CONSOLE APPLICATION
 * 
 * This is the old console entry point.
 * The application has been migrated to Spring Boot.
 * 
 * To run the new REST API, use:
 *   mvn spring-boot:run
 * 
 * or run the Application.kt file:
 *   java -jar target/cs2skins_v2-1.0-SNAPSHOT.jar
 * 
 * See API_DOCUMENTATION.md for API endpoints.
 */
suspend fun main() {
    // Initialize database
    DatabaseFactory.init()

//    val seedDB = SeedDB()
//    seedDB.seedCollections()
//    seedDB.seedSkins()

    TradeUpOptimizer().optimizeAll()

}

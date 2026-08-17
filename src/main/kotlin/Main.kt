import database.clickhouse.ClickHouseClientFactory
import database.clickhouse.ItemPriceHistoryWriter
import database.clickhouse.TradeupOutcomeSnapshotWriter
import database.clickhouse.TradeupSnapshotWriter
import database.postgres.*
import tradeup.TradeUpOptimizer

suspend fun main() {
    val postgresFactory = PostgresDatabaseFactory()
    val clickHouseFactory = ClickHouseClientFactory()
    clickHouseFactory.testConnection()

    val catalogRepository = CatalogRepository(postgresFactory.dataSource())
    val recipeRepository = TradeUpRecipeRepository(postgresFactory.dataSource())
    val runRepository = CalculatorRunRepository(postgresFactory.dataSource())
    val snapshotWriter = TradeupSnapshotWriter(clickHouseFactory)
    val outcomeWriter = TradeupOutcomeSnapshotWriter(clickHouseFactory)
    val priceHistoryWriter = ItemPriceHistoryWriter(clickHouseFactory)
    val recipeOutcomeRepository = TradeUpRecipeOutcomeRepository(postgresFactory.dataSource())
    // New: rating (Part 2 of the DB-rating work).
    val bestTradeUpByPairRepository = BestTradeUpByPairRepository(postgresFactory.dataSource(), clickHouseFactory)

    val seedService = PostgresSeedService(catalogRepository)
    println("Seeding collections...")
    seedService.seedCollections()
    println("Seeding items...")
    seedService.seedSkins()

    println("Ingesting prices...")
    val priceService = PriceIngestionService(catalogRepository, priceHistoryWriter)
    priceService.ingestCurrentPrices()          // fast, every run
    priceService.ingestSteamMetrics()        // slow -- run manually/scheduled, not every time

    println("Running trade-up optimizer...")
    val optimizer = TradeUpOptimizer(
        catalogRepository,
        recipeRepository,
        recipeOutcomeRepository,
        runRepository,
        snapshotWriter,
        outcomeWriter,
        bestTradeUpByPairRepository
    )
    optimizer.optimizeAll()

    println("Run completed.")
    postgresFactory.close()
}

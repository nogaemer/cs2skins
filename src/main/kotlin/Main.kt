import database.clickhouse.ClickHouseClientFactory
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
    val recipeOutcomeRepository = TradeUpRecipeOutcomeRepository(postgresFactory.dataSource())

    val seedService = PostgresSeedService(catalogRepository)
    println("Seeding collections...")
    seedService.seedCollections()

    println("Seeding items...")
    seedService.seedSkins()

    println("Ingesting prices...")
    val priceService = PriceIngestionService(catalogRepository)
    priceService.ingestCurrentPrices()

    println("Running trade-up optimizer...")
    val optimizer = TradeUpOptimizer(
        catalogRepository, recipeRepository, recipeOutcomeRepository,
        runRepository, snapshotWriter, outcomeWriter
    )
    optimizer.optimizeAll()

    println("Run completed.")
    postgresFactory.close()
}
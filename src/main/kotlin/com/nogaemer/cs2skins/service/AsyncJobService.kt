package com.nogaemer.cs2skins.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class AsyncJobService(
    private val seedService: SeedService,
    private val tradeUpService: TradeUpService
) {
    private val logger = LoggerFactory.getLogger(AsyncJobService::class.java)

    @Async("taskExecutor")
    fun seedCollectionsAsync(): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("Starting collections seed job")
                runBlocking {
                    seedService.seedCollections()
                }
                logger.info("Collections seed job completed successfully")
            } catch (e: Exception) {
                logger.error("Collections seed job failed", e)
                throw e
            }
        }
    }

    @Async("taskExecutor")
    fun seedSkinsAsync(): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("Starting skins seed job")
                runBlocking {
                    seedService.seedSkins()
                }
                logger.info("Skins seed job completed successfully")
            } catch (e: Exception) {
                logger.error("Skins seed job failed", e)
                throw e
            }
        }
    }

    @Async("taskExecutor")
    fun seedAllAsync(): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("Starting full seed job (collections + skins)")
                runBlocking {
                    seedService.seedAll()
                }
                logger.info("Full seed job completed successfully")
            } catch (e: Exception) {
                logger.error("Full seed job failed", e)
                throw e
            }
        }
    }

    @Async("taskExecutor")
    fun generateMastersAsync(stattrak: Boolean): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("Starting master definition generation job (stattrak: $stattrak)")
                runBlocking {
                    tradeUpService.generateMasterDefinitions(stattrak)
                }
                logger.info("Master definition generation job completed successfully")
            } catch (e: Exception) {
                logger.error("Master definition generation job failed", e)
                throw e
            }
        }
    }

    @Async("taskExecutor")
    fun calculatePricesAsync(stattrak: Boolean): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("Starting price calculation job (stattrak: $stattrak)")
                runBlocking {
                    tradeUpService.calculatePricesForMasters(stattrak)
                }
                logger.info("Price calculation job completed successfully")
            } catch (e: Exception) {
                logger.error("Price calculation job failed", e)
                throw e
            }
        }
    }

    @Async("taskExecutor")
    fun calculateTradeUpsAsync(stattrak: Boolean): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("Starting trade-up calculation job (stattrak: $stattrak)")
                runBlocking {
                    tradeUpService.calculateAndSaveTradeUps(stattrak)
                }
                logger.info("Trade-up calculation job completed successfully")
            } catch (e: Exception) {
                logger.error("Trade-up calculation job failed", e)
                throw e
            }
        }
    }

    @Async("taskExecutor")
    fun calculateAllTradeUpsAsync(): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("Starting full trade-up calculation job (non-stattrak + stattrak)")
                runBlocking {
                    // Calculate for non-stattrak
                    tradeUpService.calculateAndSaveTradeUps(false)
                    // Calculate for stattrak
                    tradeUpService.calculateAndSaveTradeUps(true)
                }
                logger.info("Full trade-up calculation job completed successfully")
            } catch (e: Exception) {
                logger.error("Full trade-up calculation job failed", e)
                throw e
            }
        }
    }
}

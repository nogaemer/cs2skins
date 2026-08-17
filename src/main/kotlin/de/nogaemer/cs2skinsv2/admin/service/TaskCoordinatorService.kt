package de.nogaemer.cs2skinsv2.admin.service

import de.nogaemer.cs2skinsv2.pricing.service.PriceIngestionService
import de.nogaemer.cs2skinsv2.tradeup.repository.CalculatorRunRepository
import de.nogaemer.cs2skinsv2.tradeup.service.TradeUpOptimizer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

@Service
class TaskCoordinatorService(
    private val priceIngestionService: PriceIngestionService,
    private val tradeUpOptimizer: TradeUpOptimizer,
    private val calculatorRunRepository: CalculatorRunRepository
) {
    private val log = LoggerFactory.getLogger(TaskCoordinatorService::class.java)

    private val priceInProgress = AtomicBoolean(false)
    private val metricsInProgress = AtomicBoolean(false)

    fun startPriceIngestion(): Boolean =
        priceInProgress.compareAndSet(false, true).also { started ->
            if (started) {
                CompletableFuture.runAsync {
                    try {
                        priceIngestionService.ingestCurrentPrices()
                    } catch (e: Exception) {
                        log.error("Price ingestion failed", e)
                    } finally {
                        priceInProgress.set(false)
                    }
                }
            }
        }

    fun startMetricsIngestion(): Boolean =
        metricsInProgress.compareAndSet(false, true).also { started ->
            if (started) {
                CompletableFuture.runAsync {
                    try {
                        priceIngestionService.ingestSteamMetrics()
                    } catch (e: Exception) {
                        log.error("Steam metrics enrichment failed", e)
                    } finally {
                        metricsInProgress.set(false)
                    }
                }
            }
        }

    fun startOptimizationAsync(): Long? {
        val running = calculatorRunRepository.findRunningRun()
        if (running != null) return null

        val runId = calculatorRunRepository.startRun(
            intervalLabel = "manual",
            calculatorVersion = "1.0.0"
        )

        CompletableFuture.runAsync {
            try {
                tradeUpOptimizer.optimizeAll(runId)
            } catch (e: Exception) {
                log.error("Optimization failed for run $runId", e)
            }
        }

        return runId
    }

    fun runDailyMetricsThenOptimize() {
        val running = calculatorRunRepository.findRunningRun()
        if (running != null) {
            log.warn("Scheduled metrics+optimize skipped because run {} is RUNNING", running.id)
            return
        }
        if (!metricsInProgress.compareAndSet(false, true)) {
            log.warn("Scheduled metrics+optimize skipped because metrics ingestion is already in progress")
            return
        }

        try {
            log.info("Starting scheduled Steam metrics enrichment")
            priceIngestionService.ingestSteamMetrics()

            log.info("Starting scheduled optimization")
            val runId = calculatorRunRepository.startRun(
                intervalLabel = "scheduled",
                calculatorVersion = "1.0.0"
            )
            tradeUpOptimizer.optimizeAll(runId)
        } catch (e: Exception) {
            log.error("Scheduled metrics+optimize failed", e)
        } finally {
            metricsInProgress.set(false)
        }
    }
}
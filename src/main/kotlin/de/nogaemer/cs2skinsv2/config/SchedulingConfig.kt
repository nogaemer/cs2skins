package de.nogaemer.cs2skinsv2.config

import de.nogaemer.cs2skinsv2.admin.service.TaskCoordinatorService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class IngestionScheduler(
    private val taskCoordinatorService: TaskCoordinatorService
) {

    @Scheduled(cron = "\${app.scheduling.daily-price-cron:0 0 2 * * ?}")
    fun schedulePriceIngestion() {
        taskCoordinatorService.startPriceIngestion()
    }

    @Scheduled(cron = "\${app.scheduling.daily-metrics-cron:0 0 3 * * ?}")
    fun scheduleDailyMetricsAndOptimization() {
        taskCoordinatorService.runDailyMetricsThenOptimize()
    }
}
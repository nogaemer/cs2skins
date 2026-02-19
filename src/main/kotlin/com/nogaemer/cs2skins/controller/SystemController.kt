package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.JobStatusResponse
import com.nogaemer.cs2skins.service.AsyncJobService
import com.nogaemer.cs2skins.service.TradeUpService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/api/system")
class SystemController(
    private val asyncJobService: AsyncJobService,
    private val tradeUpService: TradeUpService
) {

    private val seedJobRunning = AtomicBoolean(false)
    private val calculateJobRunning = AtomicBoolean(false)

    @PostMapping("/seed/collections")
    fun seedCollections(): ResponseEntity<JobStatusResponse> {
        if (!seedJobRunning.compareAndSet(false, true)) {
            return ResponseEntity.ok(JobStatusResponse("running", "Seed job is already running"))
        }

        asyncJobService.seedCollectionsAsync()
            .whenComplete { _, error ->
                seedJobRunning.set(false)
                error?.let { 
                    // Exception already logged in AsyncJobService
                }
            }

        return ResponseEntity.ok(JobStatusResponse("started", "Collection seed job started"))
    }

    @PostMapping("/seed/skins")
    fun seedSkins(): ResponseEntity<JobStatusResponse> {
        if (!seedJobRunning.compareAndSet(false, true)) {
            return ResponseEntity.ok(JobStatusResponse("running", "Seed job is already running"))
        }

        asyncJobService.seedSkinsAsync()
            .whenComplete { _, error ->
                seedJobRunning.set(false)
                error?.let { 
                    // Exception already logged in AsyncJobService
                }
            }

        return ResponseEntity.ok(JobStatusResponse("started", "Skins seed job started"))
    }

    @PostMapping("/seed/all")
    fun seedAll(): ResponseEntity<JobStatusResponse> {
        if (!seedJobRunning.compareAndSet(false, true)) {
            return ResponseEntity.ok(JobStatusResponse("running", "Seed job is already running"))
        }

        asyncJobService.seedAllAsync()
            .whenComplete { _, error ->
                seedJobRunning.set(false)
                error?.let { 
                    // Exception already logged in AsyncJobService
                }
            }

        return ResponseEntity.ok(JobStatusResponse("started", "Full seed job started (collections + skins)"))
    }

    @PostMapping("/calculate")
    fun calculateTradeUps(
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<JobStatusResponse> {
        if (!calculateJobRunning.compareAndSet(false, true)) {
            return ResponseEntity.ok(JobStatusResponse("running", "Calculate job is already running"))
        }

        asyncJobService.calculateTradeUpsAsync(stattrak)
            .whenComplete { _, error ->
                calculateJobRunning.set(false)
                error?.let { 
                    // Exception already logged in AsyncJobService
                }
            }

        return ResponseEntity.ok(JobStatusResponse("started", "Trade-up calculation job started"))
    }

    @PostMapping("/calculate/all")
    fun calculateAllTradeUps(): ResponseEntity<JobStatusResponse> {
        if (!calculateJobRunning.compareAndSet(false, true)) {
            return ResponseEntity.ok(JobStatusResponse("running", "Calculate job is already running"))
        }

        asyncJobService.calculateAllTradeUpsAsync()
            .whenComplete { _, error ->
                calculateJobRunning.set(false)
                error?.let { 
                    // Exception already logged in AsyncJobService
                }
            }

        return ResponseEntity.ok(JobStatusResponse("started", "Full trade-up calculation job started (non-stattrak + stattrak)"))
    }

    @GetMapping("/status")
    fun getSystemStatus(): ResponseEntity<Map<String, Any>> {
        val gmTotal = tradeUpService.generateMastersTotal.get()
        val gmProcessed = tradeUpService.generateMastersProcessed.get()
        val cpTotal = tradeUpService.calculatePricesTotal.get()
        val cpProcessed = tradeUpService.calculatePricesProcessed.get()

        fun progressPercent(processed: Long, total: Long): Double =
            if (total > 0) (processed.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0

        return ResponseEntity.ok(mapOf(
            "seedJobRunning" to seedJobRunning.get(),
            "calculateJobRunning" to calculateJobRunning.get(),
            "generateMasters" to mapOf(
                "processed" to gmProcessed,
                "total" to gmTotal,
                "progressPercent" to progressPercent(gmProcessed, gmTotal)
            ),
            "calculatePrices" to mapOf(
                "processed" to cpProcessed,
                "total" to cpTotal,
                "progressPercent" to progressPercent(cpProcessed, cpTotal)
            )
        ))
    }
}

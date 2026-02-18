package com.nogaemer.cs2skins.controller

import com.nogaemer.cs2skins.dto.JobStatusResponse
import com.nogaemer.cs2skins.service.SeedService
import com.nogaemer.cs2skins.service.TradeUpService
import kotlinx.coroutines.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/system")
class SystemController(
    private val seedService: SeedService,
    private val tradeUpService: TradeUpService
) {

    private var seedJobRunning = false
    private var calculateJobRunning = false

    @PostMapping("/seed/collections")
    fun seedCollections(): ResponseEntity<JobStatusResponse> {
        if (seedJobRunning) {
            return ResponseEntity.ok(JobStatusResponse("running", "Seed job is already running"))
        }

        seedJobRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                seedService.seedCollections()
            } finally {
                seedJobRunning = false
            }
        }

        return ResponseEntity.ok(JobStatusResponse("started", "Collection seed job started"))
    }

    @PostMapping("/seed/skins")
    fun seedSkins(): ResponseEntity<JobStatusResponse> {
        if (seedJobRunning) {
            return ResponseEntity.ok(JobStatusResponse("running", "Seed job is already running"))
        }

        seedJobRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                seedService.seedSkins()
            } finally {
                seedJobRunning = false
            }
        }

        return ResponseEntity.ok(JobStatusResponse("started", "Skins seed job started"))
    }

    @PostMapping("/seed/all")
    fun seedAll(): ResponseEntity<JobStatusResponse> {
        if (seedJobRunning) {
            return ResponseEntity.ok(JobStatusResponse("running", "Seed job is already running"))
        }

        seedJobRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                seedService.seedAll()
            } finally {
                seedJobRunning = false
            }
        }

        return ResponseEntity.ok(JobStatusResponse("started", "Full seed job started (collections + skins)"))
    }

    @PostMapping("/calculate")
    fun calculateTradeUps(
        @RequestParam(defaultValue = "false") stattrak: Boolean
    ): ResponseEntity<JobStatusResponse> {
        if (calculateJobRunning) {
            return ResponseEntity.ok(JobStatusResponse("running", "Calculate job is already running"))
        }

        calculateJobRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tradeUpService.calculateAndSaveTradeUps(stattrak)
            } finally {
                calculateJobRunning = false
            }
        }

        return ResponseEntity.ok(JobStatusResponse("started", "Trade-up calculation job started"))
    }

    @PostMapping("/calculate/all")
    fun calculateAllTradeUps(): ResponseEntity<JobStatusResponse> {
        if (calculateJobRunning) {
            return ResponseEntity.ok(JobStatusResponse("running", "Calculate job is already running"))
        }

        calculateJobRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Calculate for non-stattrak
                tradeUpService.calculateAndSaveTradeUps(false)
                // Calculate for stattrak
                tradeUpService.calculateAndSaveTradeUps(true)
            } finally {
                calculateJobRunning = false
            }
        }

        return ResponseEntity.ok(JobStatusResponse("started", "Full trade-up calculation job started (non-stattrak + stattrak)"))
    }

    @GetMapping("/status")
    fun getSystemStatus(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "seedJobRunning" to seedJobRunning,
            "calculateJobRunning" to calculateJobRunning
        ))
    }
}

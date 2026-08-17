package de.nogaemer.cs2skinsv2.admin.controller

import de.nogaemer.cs2skinsv2.admin.dto.*
import de.nogaemer.cs2skinsv2.admin.service.TaskCoordinatorService
import de.nogaemer.cs2skinsv2.common.exception.ConflictException
import de.nogaemer.cs2skinsv2.common.exception.NotFoundException
import de.nogaemer.cs2skinsv2.tradeup.repository.CalculatorRunRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val taskCoordinatorService: TaskCoordinatorService,
    private val calculatorRunRepository: CalculatorRunRepository
) {

    @PostMapping("/optimize")
    fun triggerOptimization(): ResponseEntity<OptimizeStartResponse> {
        val runId = taskCoordinatorService.startOptimizationAsync()
            ?: throw ConflictException(
                "A calculator run (id=${calculatorRunRepository.findRunningRun()?.id}) " +
                        "is already RUNNING. Wait for it to finish before starting another."
            )

        val run = calculatorRunRepository.findById(runId)
            ?: throw NotFoundException("No calculator run found with id $runId")

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            OptimizeStartResponse(runId, "RUNNING", run.startedAt)
        )
    }

    @GetMapping("/runs/{runId}")
    fun getRun(@PathVariable runId: Long): RunDetailDto {
        val run = calculatorRunRepository.findById(runId)
            ?: throw NotFoundException("No calculator run found with id $runId")

        return RunDetailDto(
            runId = run.id,
            status = run.status,
            intervalLabel = run.intervalLabel,
            calculatorVersion = run.calculatorVersion,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            rowCount = run.rowCount,
            errorMessage = run.errorMessage
        )
    }

    @GetMapping("/runs")
    fun listRuns(): RunListResponse {
        val runs = calculatorRunRepository.findRecent(100).map { run ->
            RunSummaryDto(
                runId = run.id,
                status = run.status,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                rowCount = run.rowCount
            )
        }
        return RunListResponse(runs)
    }

    @PostMapping("/ingest/prices")
    fun triggerPriceIngestion(): ResponseEntity<IngestionStartResponse> {
        if (!taskCoordinatorService.startPriceIngestion()) {
            throw ConflictException("Price ingestion is already in progress.")
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            IngestionStartResponse("STARTED", "Price ingestion started in the background.")
        )
    }

    @PostMapping("/ingest/metrics")
    fun triggerMetricsIngestion(): ResponseEntity<IngestionStartResponse> {
        if (!taskCoordinatorService.startMetricsIngestion()) {
            throw ConflictException("Steam metrics enrichment is already in progress.")
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            IngestionStartResponse(
                "STARTED",
                "Steam metrics enrichment started in the background. " +
                        "This can take 45+ minutes for the full catalog."
            )
        )
    }
}
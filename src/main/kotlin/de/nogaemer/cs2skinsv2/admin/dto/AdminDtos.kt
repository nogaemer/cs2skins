package de.nogaemer.cs2skinsv2.admin.dto

import java.time.OffsetDateTime

data class OptimizeStartResponse(
    val runId: Long,
    val status: String,
    val startedAt: OffsetDateTime
)

data class IngestionStartResponse(
    val status: String,
    val message: String
)

data class RunSummaryDto(
    val runId: Long,
    val status: String,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val rowCount: Long?
)

data class RunDetailDto(
    val runId: Long,
    val status: String,
    val intervalLabel: String,
    val calculatorVersion: String,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val rowCount: Long?,
    val errorMessage: String?
)

data class RunListResponse(
    val content: List<RunSummaryDto>
)
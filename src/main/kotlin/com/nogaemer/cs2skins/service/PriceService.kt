package com.nogaemer.cs2skins.service

import com.nogaemer.cs2skins.dto.BucketedPriceResponse
import com.nogaemer.cs2skins.dto.LatestPriceResponse
import com.nogaemer.cs2skins.dto.RawPriceHistoryResponse
import database.PriceHistoryParams
import database.SkinPriceRepository
import org.springframework.stereotype.Service

@Service
class PriceService(
    private val skinPriceRepository: SkinPriceRepository = SkinPriceRepository()
) {

    suspend fun getLatestPrices(
        skinId: String,
        source: String?,
        currency: String?
    ): List<LatestPriceResponse> = skinPriceRepository.getLatestPrices(skinId, source, currency)

    suspend fun getRawPriceHistory(params: PriceHistoryParams): List<RawPriceHistoryResponse> =
        skinPriceRepository.getRawPriceHistory(params)

    suspend fun getBucketedPriceHistory(
        params: PriceHistoryParams,
        bucket: String
    ): List<BucketedPriceResponse> = skinPriceRepository.getBucketedPriceHistory(params, bucket)
}

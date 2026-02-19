package com.nogaemer.cs2skins.service

import database.SeedDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class SeedService {
    private val seedDB = SeedDB()

    suspend fun seedCollections() = withContext(Dispatchers.IO) {
        seedDB.seedCollections()
    }

    suspend fun seedSkins() = withContext(Dispatchers.IO) {
        seedDB.seedSkins()
    }

    suspend fun seedAll() = withContext(Dispatchers.IO) {
        seedDB.seedCollections()
        seedDB.seedSkins()
    }
}

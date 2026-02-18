package com.nogaemer.cs2skins

import database.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.runApplication
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@SpringBootApplication(scanBasePackages = ["com.nogaemer.cs2skins", "database"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

@Component
class DatabaseInitializer {
    @EventListener(ApplicationReadyEvent::class)
    fun initializeTables() {
        transaction {
            SchemaUtils.create(
                Collections,
                Weapons,
                Rarities,
                WearConditions,
                Skins,
                SkinPrices,
                TradeUpResults,
                TradeUpInputs,
                TradeUpOutputs
            )
        }
    }
}

package de.nogaemer.cs2skinsv2

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * TEMPORARY: isolates BestTradeUpByPairRepository.refresh() so it can be
 * debugged directly against the 11.2M rows already sitting in ClickHouse's
 * tradeup_snapshot_latest -- no need to re-run the multi-hour optimizeAll()
 * pass just to test this one piece.
 *
 * Prints the full stack trace on failure (rather than letting Spring Boot's
 * default error handling swallow/truncate it), and reports the resulting
 * row count in Postgres's best_tradeup_by_skin_pair either way, so we get a
 * definitive answer on whether refresh() actually inserted anything.
 */
@SpringBootApplication
@EnableScheduling
class Cs2SkinsApplication

fun main(args: Array<String>) {
    runApplication<Cs2SkinsApplication>(*args)
}

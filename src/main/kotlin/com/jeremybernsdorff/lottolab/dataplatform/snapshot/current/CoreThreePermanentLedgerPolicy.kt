package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Path
import java.time.LocalDate

internal object CoreThreePermanentLedgerPolicy {
    val games = listOf("powerball", "mega_millions", "lotto_america")

    private val inceptionDates = mapOf(
        "powerball" to LocalDate.parse("2026-08-17"),
        "mega_millions" to LocalDate.parse("2026-09-01"),
        "lotto_america" to LocalDate.parse("2026-08-29")
    )

    private val bootstrapBaseCutoffs = mapOf(
        "powerball" to LocalDate.parse("2026-08-15"),
        "mega_millions" to LocalDate.parse("2026-08-28"),
        "lotto_america" to LocalDate.parse("2026-08-26")
    )

    fun inceptionDate(gameId: String): LocalDate = inceptionDates[gameId]
        ?: throw IllegalArgumentException("UNSUPPORTED_LEDGER_GAME: $gameId")

    fun bootstrapBaseCutoff(gameId: String): LocalDate = bootstrapBaseCutoffs[gameId]
        ?: throw IllegalArgumentException("UNSUPPORTED_LEDGER_GAME: $gameId")

    fun ledgerPath(ledgerRoot: Path, gameId: String, drawDate: LocalDate): Path {
        require(gameId in games) { "UNSUPPORTED_LEDGER_GAME: $gameId" }
        return ledgerRoot.resolve(gameId).resolve("$drawDate.json")
    }
}

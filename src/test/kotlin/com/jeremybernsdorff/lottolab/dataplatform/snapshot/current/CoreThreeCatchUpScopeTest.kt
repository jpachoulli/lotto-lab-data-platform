package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.OfficialDrawObservation
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreThreeCatchUpScopeTest {

    private val pbBase = LocalDate.parse("2026-08-15")
    private val mmBase = LocalDate.parse("2026-08-28")
    private val laBase = LocalDate.parse("2026-08-26")

    @Test
    fun ledgerBeforeInceptionFails() {
        val root = Files.createTempDirectory("catchup-before-inception")
        try {
            val target = root.resolve("powerball/2026-08-16.json")
            Files.createDirectories(target.parent)
            Files.copy(
                Path.of("data/current/core_three/verified/powerball/2026-08-17.json"),
                target
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                service(LocalDate.parse("2026-08-16")).catchUp(
                    Path.of("unused"), root, Instant.EPOCH
                )
            }
            assertTrue(failure.message?.contains("LEDGER_BEFORE_INCEPTION") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun offSchedulePendingLedgerFails() {
        val root = Files.createTempDirectory("catchup-off-schedule")
        try {
            write(root, validDraw("powerball", LocalDate.parse("2026-08-18")))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(LocalDate.parse("2026-08-18")).catchUp(
                    Path.of("unused"), root, Instant.EPOCH
                )
            }
            assertTrue(failure.message?.contains("OFF_SCHEDULE_LEDGER_DRAW") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun noncontiguousPendingLedgerFails() {
        val root = Files.createTempDirectory("catchup-noncontiguous")
        try {
            write(root, validDraw("powerball", LocalDate.parse("2026-08-19")))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(LocalDate.parse("2026-08-19")).catchUp(
                    Path.of("unused"), root, Instant.EPOCH
                )
            }
            assertTrue(failure.message?.contains("LEDGER_NONCONTIGUOUS_PREFIX") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun ledgerBeyondMatureWindowFails() {
        val root = Files.createTempDirectory("catchup-beyond-mature")
        try {
            write(root, validDraw("powerball", LocalDate.parse("2026-08-17")))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(LocalDate.parse("2026-08-16")).catchUp(
                    Path.of("unused"), root, Instant.EPOCH
                )
            }
            assertTrue(failure.message?.contains("LEDGER_BEYOND_MATURE_WINDOW") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun validPendingPrefixIsReusedWithoutAcquisition() {
        val root = Files.createTempDirectory("catchup-valid-prefix")
        try {
            write(root, validDraw("powerball", LocalDate.parse("2026-08-17")))
            write(root, validDraw("powerball", LocalDate.parse("2026-08-19")))
            var acquisitionCalls = 0
            val summary = CoreThreeCatchUpService(
                baseLoader = { minimalBase() },
                acquire = { _, _, _ ->
                    acquisitionCalls++
                    error("ACQUISITION_MUST_NOT_BE_CALLED")
                },
                matureThrough = { LocalDate.parse("2026-08-19") }
            ).catchUp(Path.of("unused"), root, Instant.EPOCH)

            assertEquals(0, acquisitionCalls)
            assertEquals(
                listOf(LocalDate.parse("2026-08-17"), LocalDate.parse("2026-08-19")),
                summary.games.getValue("powerball").reusedLedgerDates
            )
            assertEquals(
                LocalDate.parse("2026-08-19"),
                summary.games.getValue("powerball").verifiedFrontier
            )
        } finally {
            deleteTree(root)
        }
    }

    private fun service(mature: LocalDate): CoreThreeCatchUpService =
        CoreThreeCatchUpService(
            baseLoader = { minimalBase() },
            acquire = { _, _, _ -> error("ACQUISITION_MUST_NOT_BE_CALLED") },
            matureThrough = { mature }
        )

    private fun minimalBase(): PublishedBaseSnapshot {
        val members = mapOf(
            "draws/powerball.csv" to oneDrawCsv("powerball", pbBase),
            "draws/mega_millions.csv" to oneDrawCsv("mega_millions", mmBase),
            "draws/lotto_america.csv" to oneDrawCsv("lotto_america", laBase)
        )
        return PublishedBaseSnapshot(
            descriptor = B2_JSON.createObjectNode(),
            members = members,
            manifest = B2_JSON.createObjectNode()
        )
    }

    private fun oneDrawCsv(game: String, date: LocalDate): ByteArray {
        val era = NationalGameEraCatalog.resolve(game, date).single()
        val (main, bonus) = numbers(game)
        val header = listOf(
            "stable_id", "game_id", "game_era_id", "draw_date",
            "draw_timestamp_millis", "draw_session", "main_1", "main_2",
            "main_3", "main_4", "main_5", "bonus"
        )
        val row = listOf(
            "$game-$date", game, era.eraId, date.toString(), "", "DRAW"
        ) + main.map { it.toString() } + listOf(bonus.toString())
        return StrictCsvCodec.write(listOf(header, row))
    }

    private fun validDraw(game: String, date: LocalDate): VerifiedCurrentDraw {
        val (main, bonus) = numbers(game)
        val sources = when (game) {
            "powerball" -> listOf("ny_gaming_commission_powerball", "delaware_lottery_powerball")
            "mega_millions" -> listOf("ny_gaming_commission_mega_millions", "delaware_lottery_mega_millions")
            "lotto_america" -> listOf("musl_lotto_america", "iowa_lottery_lotto_america")
            else -> error("unsupported game")
        }
        val observations = sources.mapIndexed { index, sourceId ->
            OfficialDrawObservation(
                game, date, main, listOf(bonus), sourceId, "Test Official $index",
                "https://example.test/$sourceId", Instant.parse("2026-09-05T15:00:00Z"),
                (if (index == 0) "a" else "b").repeat(64), emptyMap()
            )
        }
        return VerifiedCurrentDraw(
            "$game-$date", game,
            NationalGameEraCatalog.resolve(game, date).single().eraId,
            date, main, listOf(bonus), observations
        )
    }

    private fun write(root: Path, draw: VerifiedCurrentDraw) {
        val path = CoreThreePermanentLedgerPolicy.ledgerPath(root, draw.gameId, draw.drawDate)
        assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(path, draw))
    }

    private fun numbers(game: String): Pair<List<Int>, Int> = when (game) {
        "powerball" -> listOf(3, 10, 29, 58, 64) to 14
        "mega_millions" -> listOf(1, 22, 51, 61, 63) to 17
        "lotto_america" -> listOf(2, 4, 16, 39, 45) to 6
        else -> error("unsupported game")
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

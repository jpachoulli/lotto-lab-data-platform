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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreThreeMaterializedContinuityTest {

    private val pbHistorical = LocalDate.parse("2026-08-15")
    private val pbFirst = LocalDate.parse("2026-08-17")
    private val pbSecond = LocalDate.parse("2026-08-19")
    private val pbOffSchedule = LocalDate.parse("2026-08-18")
    private val mmBase = LocalDate.parse("2026-08-28")
    private val laBase = LocalDate.parse("2026-08-26")

    @Test
    fun missingExpectedMaterializedLedgerDrawFails() {
        val root = Files.createTempDirectory("materialized-missing-ledger")
        try {
            write(root, draw("powerball", pbFirst))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(base(listOf(pbHistorical, pbFirst, pbSecond)))
                    .catchUp(Path.of("unused"), root, Instant.EPOCH)
            }
            assertTrue(failure.message?.contains("MISSING_MATERIALIZED_PERMANENT_LEDGER") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun missingExpectedMaterializedBaseDrawFails() {
        val root = Files.createTempDirectory("materialized-missing-base")
        try {
            write(root, draw("powerball", pbFirst))
            write(root, draw("powerball", pbSecond))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(base(listOf(pbHistorical, pbSecond)))
                    .catchUp(Path.of("unused"), root, Instant.EPOCH)
            }
            assertTrue(failure.message?.contains("MATERIALIZED_LEDGER_DATE_NOT_IN_BASE") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun offScheduleMaterializedLedgerDrawFails() {
        val root = Files.createTempDirectory("materialized-offschedule-ledger")
        try {
            write(root, draw("powerball", pbFirst))
            write(root, draw("powerball", pbOffSchedule))
            write(root, draw("powerball", pbSecond))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(base(listOf(pbHistorical, pbFirst, pbSecond)))
                    .catchUp(Path.of("unused"), root, Instant.EPOCH)
            }
            assertTrue(failure.message?.contains("OFF_SCHEDULE_MATERIALIZED_LEDGER_DRAW") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun offScheduleMaterializedBaseDrawFails() {
        val root = Files.createTempDirectory("materialized-offschedule-base")
        try {
            write(root, draw("powerball", pbFirst))
            write(root, draw("powerball", pbSecond))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(base(listOf(pbHistorical, pbFirst, pbOffSchedule, pbSecond)))
                    .catchUp(Path.of("unused"), root, Instant.EPOCH)
            }
            assertTrue(failure.message?.contains("OFF_SCHEDULE_MATERIALIZED_BASE_DRAW") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun canonicalMaterializedResultMismatchFails() {
        val root = Files.createTempDirectory("materialized-result-mismatch")
        try {
            write(root, draw("powerball", pbFirst, alternate = true))
            write(root, draw("powerball", pbSecond))
            val failure = assertFailsWith<IllegalArgumentException> {
                service(base(listOf(pbHistorical, pbFirst, pbSecond)))
                    .catchUp(Path.of("unused"), root, Instant.EPOCH)
            }
            assertTrue(failure.message?.contains("MATERIALIZED_LEDGER_CANONICAL_MISMATCH") == true)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun exactMaterializedHistoryPassesWithoutAcquisition() {
        val root = Files.createTempDirectory("materialized-exact")
        try {
            write(root, draw("powerball", pbFirst))
            write(root, draw("powerball", pbSecond))
            var acquisitionCalls = 0
            val summary = CoreThreeCatchUpService(
                baseLoader = { base(listOf(pbHistorical, pbFirst, pbSecond)) },
                acquire = { _, _, _ ->
                    acquisitionCalls++
                    error("ACQUISITION_MUST_NOT_BE_CALLED")
                },
                matureThrough = { pbSecond }
            ).catchUp(Path.of("unused"), root, Instant.EPOCH)

            assertEquals(0, acquisitionCalls)
            assertEquals(pbSecond, summary.games.getValue("powerball").verifiedFrontier)
            assertFalse(summary.candidateRequired)
        } finally {
            deleteTree(root)
        }
    }

    private fun service(base: PublishedBaseSnapshot): CoreThreeCatchUpService =
        CoreThreeCatchUpService(
            baseLoader = { base },
            acquire = { _, _, _ -> error("ACQUISITION_MUST_NOT_BE_CALLED") },
            matureThrough = { pbSecond }
        )

    private fun base(powerballDates: List<LocalDate>): PublishedBaseSnapshot {
        val members = mapOf(
            "draws/powerball.csv" to gameCsv("powerball", powerballDates),
            "draws/mega_millions.csv" to gameCsv("mega_millions", listOf(mmBase)),
            "draws/lotto_america.csv" to gameCsv("lotto_america", listOf(laBase))
        )
        return PublishedBaseSnapshot(
            descriptor = B2_JSON.createObjectNode(),
            members = members,
            manifest = B2_JSON.createObjectNode()
        )
    }

    private fun gameCsv(game: String, dates: List<LocalDate>): ByteArray {
        val header = listOf(
            "stable_id", "game_id", "game_era_id", "draw_date",
            "draw_timestamp_millis", "draw_session", "main_1", "main_2",
            "main_3", "main_4", "main_5", "bonus"
        )
        val rows = dates.sorted().map { date ->
            val era = NationalGameEraCatalog.resolve(game, date).single()
            val (main, bonus) = numbers(game, alternate = false)
            listOf("$game-$date", game, era.eraId, date.toString(), "", "DRAW") +
                main.map { it.toString() } + listOf(bonus.toString())
        }
        return StrictCsvCodec.write(listOf(header) + rows)
    }

    private fun draw(game: String, date: LocalDate, alternate: Boolean = false): VerifiedCurrentDraw {
        val (main, bonus) = numbers(game, alternate)
        val sourceIds = when (game) {
            "powerball" -> listOf("ny_gaming_commission_powerball", "delaware_lottery_powerball")
            "mega_millions" -> listOf("ny_gaming_commission_mega_millions", "delaware_lottery_mega_millions")
            "lotto_america" -> listOf("musl_lotto_america", "iowa_lottery_lotto_america")
            else -> error("unsupported game")
        }
        val observations = sourceIds.mapIndexed { index, sourceId ->
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

    private fun numbers(game: String, alternate: Boolean): Pair<List<Int>, Int> = when (game) {
        "powerball" -> if (alternate) listOf(4, 11, 30, 59, 65) to 15 else listOf(3, 10, 29, 58, 64) to 14
        "mega_millions" -> listOf(1, 22, 51, 61, 63) to 17
        "lotto_america" -> listOf(2, 4, 16, 39, 45) to 6
        else -> error("unsupported game")
    }

    private fun write(root: Path, draw: VerifiedCurrentDraw) {
        val path = CoreThreePermanentLedgerPolicy.ledgerPath(root, draw.gameId, draw.drawDate)
        assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(path, draw))
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

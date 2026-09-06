package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.CurrentDrawAcquisitionResult
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

class CoreThreeCatchUpBootstrapBoundaryTest {
    private val matureThrough = LocalDate.parse("2026-09-02")
    private val bootstrapCutoffs = mapOf(
        "powerball" to LocalDate.parse("2026-08-15"),
        "mega_millions" to LocalDate.parse("2026-08-28"),
        "lotto_america" to LocalDate.parse("2026-08-26")
    )
    private val inceptions = mapOf(
        "powerball" to LocalDate.parse("2026-08-17"),
        "mega_millions" to LocalDate.parse("2026-09-01"),
        "lotto_america" to LocalDate.parse("2026-08-29")
    )

    @Test
    fun bootstrapPolicyMatchesAcceptedAugustBoundaries() {
        CoreThreePermanentLedgerPolicy.games.forEach { game ->
            assertEquals(bootstrapCutoffs.getValue(game), CoreThreePermanentLedgerPolicy.bootstrapBaseCutoff(game))
            assertEquals(inceptions.getValue(game), CoreThreePermanentLedgerPolicy.inceptionDate(game))
            assertEquals(
                listOf(inceptions.getValue(game)),
                CoreThreeCurrentSchedule.expectedDrawDates(game, bootstrapCutoffs.getValue(game), inceptions.getValue(game))
            )
        }
    }

    @Test
    fun acceptedBootstrapCanOnlyAcquireAtOrAfterInception() {
        val root = Files.createTempDirectory("catchup-bootstrap-boundary")
        try {
            val requests = mutableListOf<Pair<String, LocalDate>>()
            val summary = CoreThreeCatchUpService(
                baseLoader = { base(bootstrapCutoffs) },
                acquire = { game, date, _ ->
                    requests += game to date
                    CurrentDrawAcquisitionResult.Verified(draw(game, date))
                },
                matureThrough = { matureThrough }
            ).catchUp(Path.of("unused"), root, Instant.EPOCH)

            assertEquals(12, requests.size)
            CoreThreePermanentLedgerPolicy.games.forEach { game ->
                val gameRequests = requests.filter { it.first == game }.map { it.second }
                assertTrue(gameRequests.isNotEmpty())
                assertEquals(inceptions.getValue(game), gameRequests.first())
                assertTrue(gameRequests.all { !it.isBefore(inceptions.getValue(game)) })
            }
            assertEquals(12, summary.newlyWrittenLedgerCount)
            assertEquals(0, summary.reusedLedgerCount)
            assertTrue(summary.candidateRequired)
            assertEquals(12L, regularFileCount(root))
            requests.forEach { (game, date) ->
                val persisted = VerifiedCurrentDrawLedger.read(
                    Files.readAllBytes(CoreThreePermanentLedgerPolicy.ledgerPath(root, game, date))
                )
                CoreThreeVerifiedDrawValidator.validate(persisted, game, date)
            }
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun baseOlderThanBootstrapBoundaryFailsBeforeAcquisition() {
        val root = Files.createTempDirectory("catchup-too-old-base")
        try {
            var acquisitionCalls = 0
            val cutoffs = bootstrapCutoffs.toMutableMap().apply { put("powerball", LocalDate.parse("2026-08-12")) }
            val failure = assertFailsWith<IllegalArgumentException> {
                CoreThreeCatchUpService(
                    baseLoader = { base(cutoffs) },
                    acquire = { _, _, _ -> acquisitionCalls++; error("ACQUISITION_MUST_NOT_BE_CALLED") },
                    matureThrough = { matureThrough }
                ).catchUp(Path.of("unused"), root, Instant.EPOCH)
            }
            assertTrue(failure.message?.contains("BASE_CUTOFF_BEFORE_BOOTSTRAP_BOUNDARY") == true)
            assertEquals(0, acquisitionCalls)
            assertEquals(0L, regularFileCount(root))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun offSchedulePreInceptionBaseCutoffFailsBeforeAcquisition() {
        val root = Files.createTempDirectory("catchup-preinception-mismatch")
        try {
            var acquisitionCalls = 0
            val cutoffs = bootstrapCutoffs.toMutableMap().apply { put("powerball", LocalDate.parse("2026-08-16")) }
            val failure = assertFailsWith<IllegalArgumentException> {
                CoreThreeCatchUpService(
                    baseLoader = { base(cutoffs) },
                    acquire = { _, _, _ -> acquisitionCalls++; error("ACQUISITION_MUST_NOT_BE_CALLED") },
                    matureThrough = { matureThrough }
                ).catchUp(Path.of("unused"), root, Instant.EPOCH)
            }
            assertTrue(failure.message?.contains("PRE_INCEPTION_BASE_CUTOFF_MISMATCH") == true)
            assertEquals(0, acquisitionCalls)
            assertEquals(0L, regularFileCount(root))
        } finally {
            deleteTree(root)
        }
    }

    private fun base(cutoffs: Map<String, LocalDate>): PublishedBaseSnapshot =
        PublishedBaseSnapshot(
            B2_JSON.createObjectNode(),
            CoreThreePermanentLedgerPolicy.games.associate { game ->
                "draws/$game.csv" to oneDrawCsv(game, cutoffs.getValue(game))
            },
            B2_JSON.createObjectNode()
        )

    private fun oneDrawCsv(game: String, date: LocalDate): ByteArray {
        val era = NationalGameEraCatalog.resolve(game, date).single()
        val (main, bonus) = numbers(game)
        val header = listOf("stable_id", "game_id", "game_era_id", "draw_date", "draw_timestamp_millis", "draw_session", "main_1", "main_2", "main_3", "main_4", "main_5", "bonus")
        val row = listOf("$game-$date", game, era.eraId, date.toString(), "", "DRAW") + main.map(Int::toString) + bonus.toString()
        return StrictCsvCodec.write(listOf(header, row))
    }

    private fun draw(game: String, date: LocalDate): VerifiedCurrentDraw {
        val (main, bonus) = numbers(game)
        val sourceIds = when (game) {
            "powerball" -> listOf("ny_gaming_commission_powerball", "delaware_lottery_powerball")
            "mega_millions" -> listOf("ny_gaming_commission_mega_millions", "delaware_lottery_mega_millions")
            "lotto_america" -> listOf("musl_lotto_america", "iowa_lottery_lotto_america")
            else -> error("unsupported game")
        }
        val observations = sourceIds.mapIndexed { index, sourceId ->
            OfficialDrawObservation(
                game, date, main, listOf(bonus), sourceId, "Test Official $index", "https://example.test/$sourceId",
                Instant.parse("2026-09-05T15:00:00Z"), (if (index == 0) "a" else "b").repeat(64), emptyMap()
            )
        }
        return VerifiedCurrentDraw(
            "$game-$date", game, NationalGameEraCatalog.resolve(game, date).single().eraId,
            date, main, listOf(bonus), observations
        )
    }

    private fun numbers(game: String): Pair<List<Int>, Int> = when (game) {
        "powerball" -> listOf(3, 10, 29, 58, 64) to 14
        "mega_millions" -> listOf(1, 22, 51, 61, 63) to 17
        "lotto_america" -> listOf(2, 4, 16, 39, 45) to 6
        else -> error("unsupported game")
    }

    private fun regularFileCount(root: Path): Long = if (!Files.exists(root)) 0L else Files.walk(root).use { it.filter(Files::isRegularFile).count() }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

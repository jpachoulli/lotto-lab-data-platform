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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreThreeCatchUpBehaviorCompletionTest {
    private val descriptor =
        CoreThreeRepositoryTestFixtures.augustDescriptor
    private val sep2 = LocalDate.parse("2026-09-02")

    @Test
    fun arbitraryStaleCatchUpAcquiresEveryExpectedDateAcrossGames() {
        val root = Files.createTempDirectory("catchup-arbitrary-stale")
        try {
            val requests = mutableListOf<Pair<String, LocalDate>>()
            val summary = service(root, { game, date, _ -> requests += game to date; verified(game, date) }, { sep2 })
            assertEquals(listOf(
                "powerball" to LocalDate.parse("2026-08-17"), "powerball" to LocalDate.parse("2026-08-19"),
                "powerball" to LocalDate.parse("2026-08-22"), "powerball" to LocalDate.parse("2026-08-24"),
                "powerball" to LocalDate.parse("2026-08-26"), "powerball" to LocalDate.parse("2026-08-29"),
                "powerball" to LocalDate.parse("2026-08-31"), "powerball" to LocalDate.parse("2026-09-02"),
                "mega_millions" to LocalDate.parse("2026-09-01"),
                "lotto_america" to LocalDate.parse("2026-08-29"), "lotto_america" to LocalDate.parse("2026-08-31"),
                "lotto_america" to LocalDate.parse("2026-09-02")
            ), requests)
            assertEquals(12, summary.newlyWrittenLedgerCount)
            assertEquals(0, summary.reusedLedgerCount)
            assertTrue(summary.candidateRequired)
            assertEquals(12L, regularFileCount(root))
            requests.forEach { (game, date) -> validatePersisted(root, game, date) }
            assertEquals(LocalDate.parse("2026-09-02"), summary.games.getValue("powerball").verifiedFrontier)
            assertEquals(LocalDate.parse("2026-09-01"), summary.games.getValue("mega_millions").verifiedFrontier)
            assertEquals(LocalDate.parse("2026-09-02"), summary.games.getValue("lotto_america").verifiedFrontier)
        } finally { deleteTree(root) }
    }

    @Test
    fun existingContiguousPrefixIsReusedBeforeLaterAcquisition() {
        val root = Files.createTempDirectory("catchup-prefix-reuse")
        try {
            val aug17 = LocalDate.parse("2026-08-17")
            val aug19 = LocalDate.parse("2026-08-19")
            val aug22 = LocalDate.parse("2026-08-22")
            val aug24 = LocalDate.parse("2026-08-24")
            write(root, draw("powerball", aug17)); write(root, draw("powerball", aug19))
            val requests = mutableListOf<Pair<String, LocalDate>>()
            val summary = service(root, { game, date, _ -> requests += game to date; verified(game, date) }, { aug24 })
            assertEquals(listOf("powerball" to aug22, "powerball" to aug24), requests)
            val pb = summary.games.getValue("powerball")
            assertEquals(listOf(aug17, aug19), pb.reusedLedgerDates)
            assertEquals(listOf(aug22, aug24), pb.newlyWrittenLedgerDates)
            assertEquals(aug24, pb.verifiedFrontier)
            assertEquals(2, summary.reusedLedgerCount)
            assertEquals(2, summary.newlyWrittenLedgerCount)
        } finally { deleteTree(root) }
    }

    @Test
    fun notYetAvailableStopsLaterSameGameAcquisition() {
        val root = Files.createTempDirectory("catchup-not-yet")
        try {
            val aug17 = LocalDate.parse("2026-08-17")
            val aug19 = LocalDate.parse("2026-08-19")
            val requests = mutableListOf<LocalDate>()
            val summary = service(root, { game, date, _ ->
                requests += date
                when (date) {
                    aug17 -> verified(game, date)
                    aug19 -> CurrentDrawAcquisitionResult.NotYetAvailable
                    else -> error("LATER_DRAW_MUST_NOT_BE_REQUESTED")
                }
            }, { LocalDate.parse("2026-08-24") })
            assertEquals(listOf(aug17, aug19), requests)
            val pb = summary.games.getValue("powerball")
            assertEquals(aug17, pb.verifiedFrontier)
            assertEquals(aug19, pb.waitingOnDate)
            assertEquals("NOT_YET_AVAILABLE", pb.waitingReason)
            assertEquals(GameCatchUpState.WAITING_FOR_OFFICIAL_RESULT, pb.state)
            assertTrue(Files.exists(root.resolve("powerball/2026-08-17.json")))
            assertFalse(Files.exists(root.resolve("powerball/2026-08-19.json")))
            assertFalse(requests.contains(LocalDate.parse("2026-08-22")))
            assertFalse(requests.contains(LocalDate.parse("2026-08-24")))
        } finally { deleteTree(root) }
    }

    @Test
    fun newlyAcquiredDrawIsPersistedReadBackAndReported() {
        val root = Files.createTempDirectory("catchup-readback")
        try {
            val date = LocalDate.parse("2026-08-17")
            var calls = 0
            val summary = service(root, { game, requested, _ ->
                calls++
                assertEquals("powerball", game); assertEquals(date, requested)
                verified(game, requested)
            }, { date })
            assertEquals(1, calls)
            val path = CoreThreePermanentLedgerPolicy.ledgerPath(root, "powerball", date)
            assertTrue(Files.exists(path))
            validatePersisted(root, "powerball", date)
            assertEquals(listOf(date), summary.games.getValue("powerball").newlyWrittenLedgerDates)
            assertEquals(date, summary.games.getValue("powerball").verifiedFrontier)
        } finally { deleteTree(root) }
    }

    @Test
    fun oneWaitingGameDoesNotBlockIndependentGames() {
        val root = Files.createTempDirectory("catchup-independent-games")
        try {
            val requests = mutableListOf<Pair<String, LocalDate>>()
            val pbFirst = LocalDate.parse("2026-08-17")
            val summary = service(root, { game, date, _ ->
                requests += game to date
                if (game == "powerball" && date == pbFirst) {
                    CurrentDrawAcquisitionResult.AwaitingSecondSource(draw(game, date).observations.first())
                } else verified(game, date)
            }, { sep2 })
            val pb = summary.games.getValue("powerball")
            assertEquals(LocalDate.parse("2026-08-15"), pb.verifiedFrontier)
            assertEquals(pbFirst, pb.waitingOnDate)
            assertEquals("AWAITING_SECOND_SOURCE", pb.waitingReason)
            assertFalse(requests.contains("powerball" to LocalDate.parse("2026-08-19")))
            assertEquals(LocalDate.parse("2026-09-01"), summary.games.getValue("mega_millions").verifiedFrontier)
            assertEquals(LocalDate.parse("2026-09-02"), summary.games.getValue("lotto_america").verifiedFrontier)
            assertTrue(requests.contains("mega_millions" to LocalDate.parse("2026-09-01")))
            assertTrue(requests.contains("lotto_america" to LocalDate.parse("2026-09-02")))
            assertEquals(4, summary.newlyWrittenLedgerCount)
            assertTrue(summary.candidateRequired)
        } finally { deleteTree(root) }
    }

    @Test
    fun officialUnavailableFailsClosedWithoutLaterAcquisition() {
        val root = Files.createTempDirectory("catchup-unavailable")
        try {
            val requests = mutableListOf<Pair<String, LocalDate>>()
            val failure = assertFailsWith<IllegalStateException> {
                service(root, { game, date, _ ->
                    requests += game to date
                    CurrentDrawAcquisitionResult.Unavailable("test unavailable")
                }, { LocalDate.parse("2026-08-17") })
            }
            assertTrue(failure.message?.contains("OFFICIAL_DRAW_UNAVAILABLE") == true)
            assertEquals(listOf("powerball" to LocalDate.parse("2026-08-17")), requests)
            assertEquals(0L, regularFileCount(root))
        } finally { deleteTree(root) }
    }

    @Test
    fun ledgerPathIdentityMismatchFailsBeforeAcquisition() {
        val root = Files.createTempDirectory("catchup-path-identity")
        try {
            val wrongPath = root.resolve("powerball/2026-08-19.json")
            assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(wrongPath, draw("powerball", LocalDate.parse("2026-08-17"))))
            var calls = 0
            val failure = assertFailsWith<IllegalArgumentException> {
                service(root, { _, _, _ -> calls++; error("ACQUISITION_MUST_NOT_BE_CALLED") }, { sep2 })
            }
            assertTrue(failure.message?.contains("LEDGER_PATH_IDENTITY_MISMATCH") == true)
            assertEquals(0, calls)
        } finally { deleteTree(root) }
    }

    @Test
    fun unsupportedLedgerGameFailsBeforeAcquisition() {
        val root = Files.createTempDirectory("catchup-unsupported-game")
        try {
            val path = root.resolve("not_a_game/2026-08-17.json")
            Files.createDirectories(path.parent)
            Files.write(path, VerifiedCurrentDrawLedger.bytes(draw("powerball", LocalDate.parse("2026-08-17"))))
            var calls = 0
            val failure = assertFailsWith<IllegalArgumentException> {
                service(root, { _, _, _ -> calls++; error("ACQUISITION_MUST_NOT_BE_CALLED") }, { sep2 })
            }
            assertTrue(failure.message?.contains("UNSUPPORTED_LEDGER_GAME") == true)
            assertEquals(0, calls)
        } finally { deleteTree(root) }
    }

    private fun service(root: Path, acquire: (String, LocalDate, Instant) -> CurrentDrawAcquisitionResult, maturity: () -> LocalDate): CoreThreeCatchUpSummary =
        CoreThreeCatchUpService(acquire = acquire, matureThrough = { maturity() }).catchUp(descriptor, root, Instant.EPOCH)

    private fun verified(game: String, date: LocalDate): CurrentDrawAcquisitionResult = CurrentDrawAcquisitionResult.Verified(draw(game, date))

    private fun write(root: Path, value: VerifiedCurrentDraw) {
        assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(CoreThreePermanentLedgerPolicy.ledgerPath(root, value.gameId, value.drawDate), value))
    }

    private fun validatePersisted(root: Path, game: String, date: LocalDate) {
        val value = VerifiedCurrentDrawLedger.read(Files.readAllBytes(CoreThreePermanentLedgerPolicy.ledgerPath(root, game, date)))
        CoreThreeVerifiedDrawValidator.validate(value, game, date)
    }

    private fun draw(game: String, date: LocalDate): VerifiedCurrentDraw {
        val (main, bonus) = when (game) {
            "powerball" -> listOf(3, 10, 29, 58, 64) to 14
            "mega_millions" -> listOf(1, 22, 51, 61, 63) to 17
            "lotto_america" -> listOf(2, 4, 16, 39, 45) to 6
            else -> error("unsupported game")
        }
        val sources = when (game) {
            "powerball" -> listOf("ny_gaming_commission_powerball", "delaware_lottery_powerball")
            "mega_millions" -> listOf("ny_gaming_commission_mega_millions", "delaware_lottery_mega_millions")
            "lotto_america" -> listOf("musl_lotto_america", "iowa_lottery_lotto_america")
            else -> error("unsupported game")
        }
        val observations = sources.mapIndexed { index, source ->
            OfficialDrawObservation(game, date, main, listOf(bonus), source, "Test Official $index", "https://example.test/$source", Instant.parse("2026-09-05T15:00:00Z"), (if (index == 0) "a" else "b").repeat(64), emptyMap())
        }
        return VerifiedCurrentDraw("$game-$date", game, NationalGameEraCatalog.resolve(game, date).single().eraId, date, main, listOf(bonus), observations)
    }

    private fun regularFileCount(root: Path): Long = if (!Files.exists(root)) 0L else Files.walk(root).use { it.filter(Files::isRegularFile).count() }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

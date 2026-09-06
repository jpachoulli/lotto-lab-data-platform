package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.CurrentDrawAcquisitionResult
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.OfficialDrawObservation
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreThreeCatchUpServiceTest {
    private val descriptor =
        CoreThreeRepositoryTestFixtures.augustDescriptor

    private val permanent =
        CoreThreeRepositoryTestFixtures.permanentLedgerRoot

    @Test
    fun fixedBootstrapDryRunUsesNoNetwork() {
        val root =
            Files.createTempDirectory(
                "catchup-bootstrap-fixed"
            )

        try {
            CoreThreeRepositoryTestFixtures
                .materializeBootstrapLedger(
                    root
                )

            var calls =
                0

            val summary =
                CoreThreeCatchUpService(
                    acquire = { _, _, _ ->
                        calls++
                        error(
                            "NETWORK_SHOULD_NOT_BE_CALLED"
                        )
                    }
                ).catchUp(
                    descriptor,
                    root,
                    Instant.parse(
                        "2026-09-03T16:00:00Z"
                    )
                )

            assertEquals(0, calls)
            assertEquals(12, summary.reusedLedgerCount)
            assertEquals(0, summary.newlyWrittenLedgerCount)
            assertEquals(
                LocalDate.parse("2026-09-02"),
                summary.matureThrough
            )
            assertTrue(summary.candidateRequired)
            assertEquals(
                LocalDate.parse("2026-09-02"),
                summary.games
                    .getValue("powerball")
                    .verifiedFrontier
            )
            assertEquals(
                LocalDate.parse("2026-09-01"),
                summary.games
                    .getValue("mega_millions")
                    .verifiedFrontier
            )
            assertEquals(
                LocalDate.parse("2026-09-02"),
                summary.games
                    .getValue("lotto_america")
                    .verifiedFrontier
            )
        } finally {
            Files.walk(
                root
            ).use { stream ->
                stream
                    .sorted(
                        Comparator.reverseOrder()
                    )
                    .forEach(
                        Files::deleteIfExists
                    )
            }
        }
    }

    @Test
    fun acceptedBootstrapTwelveRemainPresentInsideGrowingPermanentLedger() {
        val bootstrap =
            CoreThreeRepositoryTestFixtures
                .requireBootstrapLedgerPresent(
                    permanent
                )

        assertEquals(
            12,
            bootstrap.size
        )

        assertTrue(
            CoreThreeRepositoryTestFixtures
                .allPermanentLedgerFiles(
                    permanent
                )
                .size >=
                bootstrap.size
        )
    }

    @Test fun waitingPreventsLaterSameGameAcquisition() {
        val root = Files.createTempDirectory("catchup-waiting")
        try {
            val requests = mutableListOf<LocalDate>()
            val summary = CoreThreeCatchUpService(acquire = { game, date, _ ->
                requests += date
                when (date.toString()) {
                    "2026-08-17", "2026-08-19" -> CurrentDrawAcquisitionResult.Verified(draw(game, date))
                    "2026-08-22" -> CurrentDrawAcquisitionResult.AwaitingSecondSource(draw(game, date).observations.first())
                    else -> error("D4_MUST_NOT_BE_REQUESTED")
                }
            }).catchUp(descriptor, root, Instant.parse("2026-08-25T16:00:00Z"))
            val game = summary.games.getValue("powerball")
            assertEquals(LocalDate.parse("2026-08-19"), game.verifiedFrontier)
            assertEquals(LocalDate.parse("2026-08-22"), game.waitingOnDate)
            assertEquals("AWAITING_SECOND_SOURCE", game.waitingReason)
            assertFalse(requests.contains(LocalDate.parse("2026-08-24")))
            assertTrue(Files.exists(root.resolve("powerball/2026-08-17.json")))
            assertTrue(Files.exists(root.resolve("powerball/2026-08-19.json")))
            assertFalse(Files.exists(root.resolve("powerball/2026-08-22.json")))
        } finally { Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test fun officialConflictFailsClosed() {
        val root = Files.createTempDirectory("catchup-conflict")
        try {
            val error = assertFailsWith<IllegalStateException> { CoreThreeCatchUpService(acquire = { game, date, _ -> CurrentDrawAcquisitionResult.Conflict(draw(game, date).observations[0], draw(game, date).observations[1], "disagree") }).catchUp(descriptor, root, Instant.parse("2026-08-20T16:00:00Z")) }
            assertTrue(error.message!!.contains("OFFICIAL_DRAW_CONFLICT"))
        } finally { Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun draw(game: String, date: LocalDate): VerifiedCurrentDraw {
        val main = when (game) { "powerball" -> listOf(3, 10, 29, 58, 64); "mega_millions" -> listOf(1, 22, 51, 61, 63); else -> listOf(2, 4, 16, 39, 45) }
        val bonus = when (game) { "powerball" -> 14; "mega_millions" -> 17; else -> 6 }
        val sources = when (game) { "powerball" -> listOf("ny_gaming_commission_powerball" to "New York", "delaware_lottery_powerball" to "Delaware"); "mega_millions" -> listOf("ny_gaming_commission_mega_millions" to "New York", "delaware_lottery_mega_millions" to "Delaware"); else -> listOf("musl_lotto_america" to "MUSL", "iowa_lottery_lotto_america" to "Iowa") }
        val observations = sources.mapIndexed { index, (id, org) -> OfficialDrawObservation(game, date, main, listOf(bonus), id, org, "https://example.test/$index", Instant.parse("2026-09-05T15:00:00Z"), (('a'.code + index).toChar().toString()).repeat(64)) }
        return VerifiedCurrentDraw("$game-$date", game, com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog.resolve(game, date).single().eraId, date, main, listOf(bonus), observations)
    }
}

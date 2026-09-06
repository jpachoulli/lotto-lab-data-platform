package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.OfficialDrawObservation
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreThreeVerifiedDrawValidatorTest {

    private val ledgerRoot =
        Path.of(
            "data/current/core_three/verified"
        )

    private fun ledgerFiles(): List<Path> =
        Files.walk(
            ledgerRoot
        ).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .sorted()
                .toList()
        }

    @Test
    fun allTwelveAcceptedPermanentLedgersValidate() {
        val files = ledgerFiles()
        assertEquals(12, files.size)
        files.forEach { path ->
            val draw =
                VerifiedCurrentDrawLedger.read(
                    Files.readAllBytes(path)
                )
            CoreThreeVerifiedDrawValidator.validate(
                draw,
                draw.gameId,
                draw.drawDate
            )
        }
    }

    @Test
    fun acceptedSourceSetsAreExact() {
        val sets =
            ledgerFiles()
                .map { path ->
                    VerifiedCurrentDrawLedger.read(
                        Files.readAllBytes(path)
                    )
                }
                .groupBy { it.gameId }
                .mapValues { (_, draws) ->
                    draws
                        .flatMap { it.observations }
                        .map { it.sourceId }
                        .toSet()
                }

        assertEquals(
            setOf(
                "ny_gaming_commission_powerball",
                "delaware_lottery_powerball"
            ),
            sets.getValue("powerball")
        )
        assertEquals(
            setOf(
                "ny_gaming_commission_mega_millions",
                "delaware_lottery_mega_millions"
            ),
            sets.getValue("mega_millions")
        )
        assertEquals(
            setOf(
                "musl_lotto_america",
                "iowa_lottery_lotto_america"
            ),
            sets.getValue("lotto_america")
        )
    }

    @Test
    fun wrongSecondSourceFailsClosed() {
        val original = readPowerball()
        val first = original.observations[0]
        val second = original.observations[1]
        val badSecond =
            OfficialDrawObservation(
                second.gameId,
                second.drawDate,
                second.mainValues,
                second.bonusValues,
                "fake_second_source",
                second.sourceOrganization,
                second.sourceUrl,
                second.retrievedAtUtc,
                second.rawSha256,
                second.drawMetadata
            )
        val mutated = original
        replaceObservation(mutated, 1, badSecond)
        val failure = assertFailsWith<IllegalArgumentException> {
            CoreThreeVerifiedDrawValidator.validate(
                mutated,
                original.gameId,
                original.drawDate
            )
        }
        assertTrue(
            failure.message?.contains("VERIFIED_SOURCE_SET_MISMATCH") == true
        )
    }

    @Test
    fun duplicateSourceFailsClosed() {
        val original = readPowerball()
        val source = original.observations[0]
        val duplicate =
            OfficialDrawObservation(
                source.gameId,
                source.drawDate,
                source.mainValues,
                source.bonusValues,
                source.sourceId,
                source.sourceOrganization,
                source.sourceUrl,
                source.retrievedAtUtc,
                source.rawSha256,
                source.drawMetadata
            )
        val mutated = original
        replaceObservation(mutated, 1, duplicate)
        val failure = assertFailsWith<IllegalArgumentException> {
            CoreThreeVerifiedDrawValidator.validate(
                mutated,
                original.gameId,
                original.drawDate
            )
        }
        assertTrue(
            failure.message?.contains("VERIFIED_DRAW_DUPLICATE_SOURCE_ID") == true
        )
    }

    @Test
    fun observationMainResultMismatchFailsClosed() {
        val original = readPowerball()
        val first = original.observations[0]
        val second = original.observations[1]
        val changedFirst =
            OfficialDrawObservation(
                first.gameId,
                first.drawDate,
                listOf(1, 2, 3, 4, 5),
                first.bonusValues,
                first.sourceId,
                first.sourceOrganization,
                first.sourceUrl,
                first.retrievedAtUtc,
                first.rawSha256,
                first.drawMetadata
            )
        val mutated = original
        replaceObservation(mutated, 0, changedFirst)
        val failure = assertFailsWith<IllegalArgumentException> {
            CoreThreeVerifiedDrawValidator.validate(
                mutated,
                original.gameId,
                original.drawDate
            )
        }
        assertTrue(
            failure.message?.contains("OBSERVATION_MAIN_RESULT_MISMATCH") == true
        )
    }

    @Test
    fun observationDateMismatchFailsClosed() {
        val original = readPowerball()
        val first = original.observations[0]
        val second = original.observations[1]
        val changedFirst =
            OfficialDrawObservation(
                first.gameId,
                first.drawDate.minusDays(1),
                first.mainValues,
                first.bonusValues,
                first.sourceId,
                first.sourceOrganization,
                first.sourceUrl,
                first.retrievedAtUtc,
                first.rawSha256,
                first.drawMetadata
            )
        val mutated = original
        replaceObservation(mutated, 0, changedFirst)
        val failure = assertFailsWith<IllegalArgumentException> {
            CoreThreeVerifiedDrawValidator.validate(
                mutated,
                original.gameId,
                original.drawDate
            )
        }
        assertTrue(
            failure.message?.contains("OBSERVATION_DATE_MISMATCH") == true
        )
    }

    private fun readPowerball(): VerifiedCurrentDraw =
        VerifiedCurrentDrawLedger.read(
            Files.readAllBytes(
                ledgerRoot.resolve("powerball/2026-09-02.json")
            )
        )

    private fun replaceObservation(
        draw: VerifiedCurrentDraw,
        index: Int,
        observation: OfficialDrawObservation
    ) {
        (draw.observations as MutableList<OfficialDrawObservation>)[index] =
            observation
    }
}

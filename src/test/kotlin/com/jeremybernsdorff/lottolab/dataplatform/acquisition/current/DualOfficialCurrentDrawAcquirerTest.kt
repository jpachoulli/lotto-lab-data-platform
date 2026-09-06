package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import com.jeremybernsdorff.lottolab.dataplatform.model.SourceEvidenceRecord
import com.jeremybernsdorff.lottolab.dataplatform.validation.CorpusContractValidator
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DualOfficialCurrentDrawAcquirerTest {
    private val date = LocalDate.parse("2026-09-02")
    private val now = Instant.parse("2026-09-03T00:00:00Z")
    private fun obs(source: String, main: List<Int> = listOf(3,10,29,58,64), bonus: List<Int> = listOf(14), game: String = "powerball", drawDate: LocalDate = date) = OfficialDrawObservation(game, drawDate, main, bonus, source, "Official", "https://example.test/$source", now, source.padEnd(64, 'a').take(64))
    private fun source(id: String, result: OfficialDrawSourceResult): OfficialDrawSource = object : OfficialDrawSource { override val gameId = "powerball"; override val sourceId = id; override fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant) = result }
    private fun acquire(a: OfficialDrawSourceResult, b: OfficialDrawSourceResult) = DualOfficialCurrentDrawAcquirer(CurrentDrawSourceRegistry(listOf(source("a", a), source("b", b)))).acquire("powerball", date, now)

    @Test fun twoOfficialSourcesAgreeReturnsVerified() { assertIs<CurrentDrawAcquisitionResult.Verified>(acquire(OfficialDrawSourceResult.Found(obs("a")), OfficialDrawSourceResult.Found(obs("b")))) }
    @Test fun oneSourceMissingReturnsAwaitingSecondSource() { assertIs<CurrentDrawAcquisitionResult.AwaitingSecondSource>(acquire(OfficialDrawSourceResult.Found(obs("a")), OfficialDrawSourceResult.NotYetPublished)) }
    @Test fun bothMissingReturnsNotYetAvailable() { assertTrue(acquire(OfficialDrawSourceResult.NotYetPublished, OfficialDrawSourceResult.NotYetPublished) is CurrentDrawAcquisitionResult.NotYetAvailable) }
    @Test fun twoSourcesDisagreeReturnsConflict() { assertTrue(acquire(OfficialDrawSourceResult.Found(obs("a")), OfficialDrawSourceResult.Found(obs("b", bonus = listOf(15))) ) is CurrentDrawAcquisitionResult.Conflict) }
    @Test fun sourceFailurePreventsVerification() { assertTrue(acquire(OfficialDrawSourceResult.Found(obs("a")), OfficialDrawSourceResult.Unavailable("offline")) is CurrentDrawAcquisitionResult.Unavailable) }
    @Test fun sourceIdMismatchFailsClosed() { assertTrue(acquire(OfficialDrawSourceResult.Found(obs("b")), OfficialDrawSourceResult.Found(obs("b"))) is CurrentDrawAcquisitionResult.Unavailable) }
    @Test fun observationGameMismatchFailsClosed() { assertTrue(acquire(OfficialDrawSourceResult.Found(obs("a", game = "mega_millions")), OfficialDrawSourceResult.Found(obs("b"))) is CurrentDrawAcquisitionResult.Unavailable) }
    @Test fun observationDateMismatchFailsClosed() { assertTrue(acquire(OfficialDrawSourceResult.Found(obs("a", drawDate = LocalDate.parse("2026-09-03"))), OfficialDrawSourceResult.Found(obs("b"))) is CurrentDrawAcquisitionResult.Unavailable) }
    @Test fun unsupportedGameFailsClosed() { assertTrue(DualOfficialCurrentDrawAcquirer(CurrentDrawSourceRegistry(emptyList())).acquire("unknown", date, now) is CurrentDrawAcquisitionResult.Unavailable) }
    @Test fun eraResolutionIsExact() { assertEquals("powerball_69_26_2015", assertIs<CurrentDrawAcquisitionResult.Verified>(acquire(OfficialDrawSourceResult.Found(obs("a")), OfficialDrawSourceResult.Found(obs("b")))).draw.eraId) }
    @Test fun verifiedDrawConvertsToTrustedCorpusRecord() {
        val verified = assertIs<CurrentDrawAcquisitionResult.Verified>(acquire(OfficialDrawSourceResult.Found(obs("a")), OfficialDrawSourceResult.Found(obs("b")))).draw
        val evidence = listOf(SourceEvidenceRecord("ev", "a", "Official", "https://example.test/a", null, now, "a".repeat(64), "1", null))
        val result = CorpusContractValidator.validate(listOf(verified.toCorpusDrawRecord("ev", "https://example.test/a")), NationalGameEraCatalog.eras, evidence, date)
        assertEquals(1, result.trustedRecords.size)
        assertEquals("OFFICIAL_VERIFIED", result.trustedRecords.single().validationStatus)
    }
}

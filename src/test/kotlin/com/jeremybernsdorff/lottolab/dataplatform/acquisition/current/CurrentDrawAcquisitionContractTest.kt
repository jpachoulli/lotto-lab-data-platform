package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CurrentDrawAcquisitionContractTest {
    @Test fun observationsDefensivelyCopyCollectionsAndRequireHttps() {
        val main = mutableListOf(1, 2, 3); val metadata = mutableMapOf("x" to "y")
        val o = OfficialDrawObservation("powerball", LocalDate.parse("2026-09-02"), main, listOf(4), "s", "Official", "https://example.test", Instant.EPOCH, "a".repeat(64), metadata)
        main += 5; metadata["z"] = "q"
        assertTrue(5 !in o.mainValues); assertTrue("z" !in o.drawMetadata)
    }
    @Test fun invalidObservationIdentityFailsClosed() {
        assertFailsWith<IllegalArgumentException> { OfficialDrawObservation("", LocalDate.now(), listOf(1), listOf(1), "s", "o", "https://x", Instant.EPOCH, "a".repeat(64)) }
    }

    private fun observation(source: String, game: String = "powerball", drawDate: LocalDate = LocalDate.parse("2026-09-02"), main: List<Int> = listOf(3, 10, 29, 58, 64)) =
        OfficialDrawObservation(game, drawDate, main, listOf(14), source, "Official", "https://example.test/$source", Instant.EPOCH, source.padEnd(64, 'a').take(64))

    @Test fun verifiedCurrentDrawRejectsWrongObservationGame() {
        assertFailsWith<IllegalArgumentException> { VerifiedCurrentDraw("powerball-2026-09-02", "powerball", "era", LocalDate.parse("2026-09-02"), listOf(3,10,29,58,64), listOf(14), listOf(observation("a"), observation("b", game = "mega_millions"))) }
    }
    @Test fun verifiedCurrentDrawRejectsWrongObservationDate() {
        assertFailsWith<IllegalArgumentException> { VerifiedCurrentDraw("powerball-2026-09-02", "powerball", "era", LocalDate.parse("2026-09-02"), listOf(3,10,29,58,64), listOf(14), listOf(observation("a"), observation("b", drawDate = LocalDate.parse("2026-09-03")))) }
    }
    @Test fun verifiedCurrentDrawRejectsDisagreeingObservationNumbers() {
        assertFailsWith<IllegalArgumentException> { VerifiedCurrentDraw("powerball-2026-09-02", "powerball", "era", LocalDate.parse("2026-09-02"), listOf(3,10,29,58,64), listOf(14), listOf(observation("a"), observation("b", main = listOf(1,2,3,4,5)))) }
    }
    @Test fun verifiedCurrentDrawRejectsDuplicateSourceIdentity() {
        assertFailsWith<IllegalArgumentException> { VerifiedCurrentDraw("powerball-2026-09-02", "powerball", "era", LocalDate.parse("2026-09-02"), listOf(3,10,29,58,64), listOf(14), listOf(observation("a"), observation("a"))) }
    }
}

package com.jeremybernsdorff.lottolab.dataplatform

import com.jeremybernsdorff.lottolab.dataplatform.fingerprint.AnalyticalDatasetFingerprint
import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AnalyticalDatasetFingerprintTest {
    private fun draw(
        stableId: String = "draw-1",
        gameId: String = "powerball",
        eraId: String = "powerball-current",
        date: String = "2025-01-01",
        session: String? = null,
        main: List<Int> = listOf(1, 2, 3, 4, 5),
        bonus: List<Int> = listOf(6),
        timestamp: Long? = null,
        sourceReference: String = "source-ref",
        status: String = "SOURCE_VALIDATED",
        origin: String = "official",
        evidenceId: String = "evidence-1"
    ) = CorpusDrawRecord(stableId, gameId, eraId, LocalDate.parse(date), timestamp, session, main, bonus,
        sourceReference, status, origin, evidenceId, false)

    @Test
    fun analyticalFingerprintMatchesIndependentNoSessionGolden() {
        val records = listOf(draw(), draw(stableId = "draw-2", date = "2025-01-02", main = listOf(2, 3, 4, 5, 6), bonus = listOf(7)))
        assertEquals("6efcd9964d12e8818aa3fffb61e2909f6c628b8b8614f3bcdb5e74443a462efe", AnalyticalDatasetFingerprint.compute(records))
    }

    @Test
    fun analyticalFingerprintNormalizesSessionAndMatchesIndependentGolden() {
        val record = draw(gameId = "pick_3", eraId = "pick_3-current", session = " midday ", main = listOf(1, 2, 3), bonus = emptyList())
        assertEquals("a6bc8abe1db6fe930baec4ab5ce0e8d6d6ccfbb3ef57f30f8dd4f2bd69934084", AnalyticalDatasetFingerprint.compute(listOf(record)))
    }

    @Test
    fun provenanceMetadataDoesNotAffectAnalyticalFingerprint() {
        val original = draw()
        val changed = draw(stableId = "other", timestamp = 99L, sourceReference = "other-source", status = "OFFICIAL_VERIFIED", origin = "import", evidenceId = "other-evidence")
        assertEquals(AnalyticalDatasetFingerprint.compute(listOf(original)), AnalyticalDatasetFingerprint.compute(listOf(changed)))
    }

    @Test
    fun analyticalContentChangesFingerprint() {
        val original = AnalyticalDatasetFingerprint.compute(listOf(draw()))
        val variants = listOf(
            draw(date = "2025-01-02"), draw(session = "evening"), draw(main = listOf(1, 2, 3, 4, 7)),
            draw(bonus = listOf(8)), draw(gameId = "other-game"), draw(eraId = "other-era")
        )
        variants.forEach { assertNotEquals(original, AnalyticalDatasetFingerprint.compute(listOf(it))) }
    }

    @Test
    fun sourceOrderingDoesNotChangeFingerprint() {
        val records = listOf(draw(), draw(stableId = "draw-2", date = "2025-01-02"))
        assertEquals(AnalyticalDatasetFingerprint.compute(records), AnalyticalDatasetFingerprint.compute(records.reversed()))
    }
}

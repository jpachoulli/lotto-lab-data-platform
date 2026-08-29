package com.jeremybernsdorff.lottolab.dataplatform

import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.GameEraRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.DrawResultRuleRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.SourceEvidenceRecord
import com.jeremybernsdorff.lottolab.dataplatform.validation.CorpusContractValidator
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorpusContractValidatorTest {
    private val evidence = SourceEvidenceRecord("e1", "s1", "Official", "https://example.test/data", null,
        Instant.parse("2025-01-01T00:00:00Z"), "a".repeat(64), "1", null)
    private fun rule(order: Boolean = false) = DrawResultRuleRecord(
        mainNumberCount = 3,
        mainMinimum = 1,
        mainMaximum = 9,
        mainNumbersUnique = true,
        orderMatters = order,
        bonusNumberCountMinimum = 1,
        bonusNumberCountMaximum = 1,
        bonusMinimum = 1,
        bonusMaximum = 9,
        bonusMayRepeat = false,
        bonusMayOverlapMain = false
    )
    private fun era(id: String = "era", from: String? = "2020-01-01", until: String? = null, order: Boolean = false) =
        GameEraRecord("game", id, from?.let(LocalDate::parse), until?.let(LocalDate::parse), rule(order))
    private fun draw(
        id: String = "d1", eraId: String = "era", date: String = "2025-01-01", session: String? = null,
        main: List<Int> = listOf(3, 1, 2), bonus: List<Int> = listOf(9), status: String = "SOURCE_VALIDATED",
        evidenceId: String = "e1", sample: Boolean = false, gameId: String = "game", source: String = "ref"
    ) = CorpusDrawRecord(id, gameId, eraId, LocalDate.parse(date), null, session, main, bonus, source, status, "official", evidenceId, sample)
    private fun validate(records: List<CorpusDrawRecord>, eras: List<GameEraRecord> = listOf(era()), evidenceRecords: List<SourceEvidenceRecord> = listOf(evidence), date: String = "2025-12-31") =
        CorpusContractValidator.validate(records, eras, evidenceRecords, LocalDate.parse(date))

    @Test
    fun unorderedGameNormalizesMainNumbersButOrderedGamePreservesOrder() {
        assertEquals(listOf(1, 2, 3), validate(listOf(draw())).trustedRecords.single().mainValues)
        assertEquals(listOf(3, 1, 2), validate(listOf(draw()), listOf(era(order = true))).trustedRecords.single().mainValues)
    }

    @Test
    fun sampleOrUntrustedOrMissingProvenanceFailsClosed() {
        val records = listOf(draw(id = "sample", sample = true), draw(id = "untrusted", status = "UNTRUSTED"),
            draw(id = "missing", evidenceId = "absent"), draw(id = "blank", source = " "))
        val result = validate(records)
        assertTrue(result.trustedRecords.isEmpty())
        assertEquals(4, result.rejectedRecords.size)
    }

    @Test
    fun eraBoundaryIsInclusiveExclusiveAndAmbiguousEraFailsClosed() {
        val old = era("old", "2020-01-01", "2025-01-01")
        val current = era("era", "2025-01-01", null)
        assertEquals(1, validate(listOf(draw(date = "2025-01-01")), listOf(old, current)).trustedRecords.size)
        assertEquals(1, validate(listOf(draw(eraId = "old", date = "2024-12-31")), listOf(old, current)).trustedRecords.size)
        val overlap = era("overlap", "2024-01-01", "2026-01-01")
        assertTrue(validate(listOf(draw()), listOf(current, overlap)).trustedRecords.isEmpty())
        assertTrue(validate(listOf(draw(eraId = "old")), listOf(old, current)).trustedRecords.isEmpty())
    }

    @Test
    fun duplicateEvidenceDeduplicatesButConflictingResultQuarantines() {
        val duplicate = validate(listOf(draw(id = "b"), draw(id = "a")))
        assertEquals("a", duplicate.trustedRecords.single().stableId)
        assertEquals(listOf("b"), duplicate.duplicateEvidenceRecords.map { it.stableId })
        val conflict = validate(listOf(draw(id = "a"), draw(id = "b", main = listOf(4, 1, 2))))
        assertTrue(conflict.trustedRecords.isEmpty())
        assertEquals(2, conflict.conflictedRecords.size)
    }

    @Test
    fun futureOrRuleInvalidRecordFailsClosedWithoutRepair() {
        val invalid = listOf(
            draw(id = "future", date = "2026-01-01"), draw(id = "count", main = listOf(1, 2)),
            draw(id = "range", main = listOf(1, 2, 10)), draw(id = "duplicate", main = listOf(1, 1, 2)),
            draw(id = "bonus", bonus = listOf(3))
        )
        val result = validate(invalid)
        assertTrue(result.trustedRecords.isEmpty())
        assertEquals(invalid, result.rejectedRecords)
    }
}

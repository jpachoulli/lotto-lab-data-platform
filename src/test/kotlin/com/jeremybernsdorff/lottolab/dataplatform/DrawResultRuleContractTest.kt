package com.jeremybernsdorff.lottolab.dataplatform

import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.DrawResultRuleRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.GameEraRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.SourceEvidenceRecord
import com.jeremybernsdorff.lottolab.dataplatform.validation.CorpusContractValidator
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DrawResultRuleContractTest {
    private val evidence = SourceEvidenceRecord(
        "evidence", "source", "Official", "https://example.test/data", null,
        Instant.parse("2025-01-01T00:00:00Z"), "a".repeat(64), "1", null
    )

    private fun draw(
        id: String,
        date: String,
        main: List<Int>,
        bonus: List<Int>
    ) = CorpusDrawRecord(
        id, "game", "era", LocalDate.parse(date), null, "Draw", main, bonus,
        "source-reference", "SOURCE_VALIDATED", "official", evidence.evidenceId, false
    )

    private fun validate(rule: DrawResultRuleRecord, records: List<CorpusDrawRecord>) =
        CorpusContractValidator.validate(
            records,
            listOf(GameEraRecord("game", "era", LocalDate.parse("2020-01-01"), null, rule)),
            listOf(evidence),
            LocalDate.parse("2025-12-31")
        )

    @Test
    fun fixedBonusCardinalityPreservesClassicDrawValidation() {
        val rule = DrawResultRuleRecord(3, 1, 9, true, false, 1, 1, 1, 9, false, false)

        assertEquals(
            1,
            validate(rule, listOf(draw("valid", "2025-01-01", listOf(3, 1, 2), listOf(9))))
                .trustedRecords.size
        )
        assertEquals(
            1,
            validate(rule, listOf(draw("missing", "2025-01-02", listOf(3, 1, 2), emptyList())))
                .rejectedRecords.size
        )
    }

    @Test
    fun optionalBonusCardinalityAcceptsZeroOrOneButRejectsTwo() {
        val rule = DrawResultRuleRecord(5, 1, 39, true, false, 0, 1, 1, 39, false, false)
        val result = validate(
            rule,
            listOf(
                draw("zero", "2025-01-01", listOf(5, 4, 3, 2, 1), emptyList()),
                draw("one", "2025-01-02", listOf(10, 9, 8, 7, 6), listOf(11)),
                draw("two", "2025-01-03", listOf(15, 14, 13, 12, 11), listOf(16, 17))
            )
        )

        assertEquals(listOf("one", "zero"), result.trustedRecords.map { it.stableId })
        assertEquals(listOf("two"), result.rejectedRecords.map { it.stableId })
    }

    @Test
    fun drawResultCardinalityIsIndependentOfTicketSelectionSemantics() {
        // Ticket selection is outside CorpusContractValidator; this rule describes the official result only.
        val rule = DrawResultRuleRecord(20, 1, 80, true, false, 0, 0, null, null, false, false)
        val twentyValues = (1..20).toList()
        val tenValues = (1..10).toList()

        assertEquals(
            1,
            validate(rule, listOf(draw("twenty", "2025-01-01", twentyValues, emptyList())))
                .trustedRecords.size
        )
        assertEquals(
            1,
            validate(rule, listOf(draw("ten", "2025-01-02", tenValues, emptyList())))
                .rejectedRecords.size
        )
    }

    @Test
    fun drawResultRuleConstructorFailsClosedOnInvalidCardinalityRanges() {
        assertFailsWith<IllegalArgumentException> {
            DrawResultRuleRecord(0, 1, 9, true, false, 0, 0, null, null, false, false)
        }
        assertFailsWith<IllegalArgumentException> {
            DrawResultRuleRecord(3, 1, 9, true, false, 2, 1, 1, 9, false, false)
        }
        assertFailsWith<IllegalArgumentException> {
            DrawResultRuleRecord(3, 1, 9, true, false, 0, 1, null, null, false, false)
        }
        assertFailsWith<IllegalArgumentException> {
            DrawResultRuleRecord(3, 1, 9, true, false, 0, 0, 1, 9, false, false)
        }
        assertFailsWith<IllegalArgumentException> {
            DrawResultRuleRecord(4, 1, 3, true, false, 0, 0, null, null, false, false)
        }
        assertFailsWith<IllegalArgumentException> {
            DrawResultRuleRecord(3, 1, 9, true, false, 0, 3, 1, 2, false, false)
        }
    }
}

package com.jeremybernsdorff.lottolab.dataplatform

import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.GameEraRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.SourceEvidenceRecord
import com.jeremybernsdorff.lottolab.dataplatform.validation.CorpusContractValidator
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NationalGameEraCatalogTest {
    private val evidence = SourceEvidenceRecord(
        "evidence", "source", "Official", "https://example.test/data", null,
        Instant.parse("2026-08-29T00:00:00Z"), "a".repeat(64), "1", null
    )

    private data class Boundary(
        val gameId: String,
        val date: String,
        val main: List<Int>,
        val bonus: Int,
        val eraId: String
    )

    private val boundaries = listOf(
        Boundary("powerball", "2012-01-14", listOf(10, 30, 36, 38, 41), 1, "powerball_59_39_2009"),
        Boundary("powerball", "2012-01-18", listOf(6, 29, 34, 44, 50), 28, "powerball_59_35_2012"),
        Boundary("powerball", "2015-10-03", listOf(6, 26, 33, 44, 46), 4, "powerball_59_35_2012"),
        Boundary("powerball", "2015-10-07", listOf(18, 30, 40, 48, 52), 9, "powerball_69_26_2015"),
        Boundary("mega_millions", "2005-06-21", listOf(9, 13, 40, 46, 50), 30, "mega_millions_52_52_2002"),
        Boundary("mega_millions", "2005-06-24", listOf(14, 43, 44, 50, 56), 7, "mega_millions_56_46_2005"),
        Boundary("mega_millions", "2013-10-15", listOf(4, 23, 30, 43, 50), 11, "mega_millions_56_46_2005"),
        Boundary("mega_millions", "2013-10-22", listOf(2, 3, 19, 52, 71), 14, "mega_millions_75_15_2013"),
        Boundary("mega_millions", "2017-10-27", listOf(17, 27, 41, 51, 52), 13, "mega_millions_75_15_2013"),
        Boundary("mega_millions", "2017-10-31", listOf(6, 28, 31, 52, 53), 12, "mega_millions_70_25_2017"),
        Boundary("mega_millions", "2025-04-04", listOf(11, 28, 35, 37, 69), 25, "mega_millions_70_25_2017"),
        Boundary("mega_millions", "2025-04-08", listOf(10, 16, 50, 60, 61), 17, "mega_millions_70_24_2025")
    )

    @Test
    fun catalogContainsExactlyTheAcceptedUniqueNationalEras() {
        assertEquals(3, NationalGameEraCatalog.forGame("powerball").size)
        assertEquals(5, NationalGameEraCatalog.forGame("mega_millions").size)
        assertEquals(8, NationalGameEraCatalog.eras.map { it.eraId }.distinct().size)
    }

    @Test
    fun matricesAndIntervalsMatchAcceptedData2gCandidatesExactly() {
        val expected = listOf(
            listOf("powerball_59_39_2009", "2009-01-07", "2012-01-18", "59", "39"),
            listOf("powerball_59_35_2012", "2012-01-18", "2015-10-07", "59", "35"),
            listOf("powerball_69_26_2015", "2015-10-07", null, "69", "26"),
            listOf("mega_millions_52_52_2002", "2002-05-17", "2005-06-24", "52", "52"),
            listOf("mega_millions_56_46_2005", "2005-06-24", "2013-10-22", "56", "46"),
            listOf("mega_millions_75_15_2013", "2013-10-22", "2017-10-31", "75", "15"),
            listOf("mega_millions_70_25_2017", "2017-10-31", "2025-04-08", "70", "25"),
            listOf("mega_millions_70_24_2025", "2025-04-08", null, "70", "24")
        )
        val actual = NationalGameEraCatalog.eras.map { era ->
            val rule = era.drawResultRule
            assertEquals(5, rule.mainNumberCount)
            assertEquals(1, rule.mainMinimum)
            assertTrue(rule.mainNumbersUnique)
            assertEquals(false, rule.orderMatters)
            assertEquals(1, rule.bonusNumberCountMinimum)
            assertEquals(1, rule.bonusNumberCountMaximum)
            assertEquals(1, rule.bonusMinimum)
            assertEquals(false, rule.bonusMayRepeat)
            assertTrue(rule.bonusMayOverlapMain)
            listOf(era.eraId, era.effectiveFrom.toString(), era.effectiveUntil?.toString(),
                rule.mainMaximum.toString(), rule.bonusMaximum.toString())
        }
        assertEquals(expected, actual)
        assertNull(NationalGameEraCatalog.eras.single { it.eraId == "powerball_69_26_2015" }.effectiveUntil)
        assertNull(NationalGameEraCatalog.eras.single { it.eraId == "mega_millions_70_24_2025" }.effectiveUntil)
    }

    @Test
    fun transitionsAreContiguousAndTransitionDatesResolveOnlyToNewEra() {
        NationalGameEraCatalog.eras.groupBy { it.gameId }.values.forEach { gameEras ->
            gameEras.zipWithNext().forEach { (old, new) ->
                assertEquals(old.effectiveUntil, new.effectiveFrom)
                assertEquals(listOf(new.eraId),
                    NationalGameEraCatalog.resolve(new.gameId, new.effectiveFrom!!).map { it.eraId })
            }
        }
    }

    @Test
    fun acceptedBoundaryVectorsResolveExactlyOnceAndValidate() {
        boundaries.forEachIndexed { index, boundary ->
            val date = LocalDate.parse(boundary.date)
            val resolved = NationalGameEraCatalog.resolve(boundary.gameId, date)
            assertEquals(listOf(boundary.eraId), resolved.map { it.eraId })
            val result = validate(resolved, draw("boundary-$index", boundary.gameId, boundary.eraId,
                boundary.date, boundary.main, listOf(boundary.bonus)))
            assertEquals(1, result.trustedRecords.size)
        }
    }

    @Test
    fun separatePoolNumericOverlapIsAccepted() {
        val era = NationalGameEraCatalog.eras.single { it.eraId == "powerball_69_26_2015" }
        val result = validate(listOf(era), draw("overlap", "powerball", era.eraId,
            "2025-01-01", listOf(1, 2, 3, 4, 5), listOf(5)))
        assertEquals(1, result.trustedRecords.size)
    }

    @Test
    fun invalidCountsRangesAndMainDuplicatesFailClosed() {
        val era = NationalGameEraCatalog.eras.single { it.eraId == "powerball_69_26_2015" }
        val invalid = listOf(
            draw("duplicate", "powerball", era.eraId, "2025-01-01", listOf(1, 1, 2, 3, 4), listOf(5)),
            draw("main-range", "powerball", era.eraId, "2025-01-02", listOf(1, 2, 3, 4, 70), listOf(5)),
            draw("bonus-range", "powerball", era.eraId, "2025-01-03", listOf(1, 2, 3, 4, 5), listOf(27)),
            draw("main-count", "powerball", era.eraId, "2025-01-04", listOf(1, 2, 3, 4), listOf(5)),
            draw("bonus-count", "powerball", era.eraId, "2025-01-05", listOf(1, 2, 3, 4, 5), emptyList())
        )
        val result = CorpusContractValidator.validate(invalid, listOf(era), listOf(evidence), LocalDate.parse("2026-08-29"))
        assertEquals(invalid, result.rejectedRecords)
        assertTrue(result.trustedRecords.isEmpty())
    }

    private fun draw(id: String, gameId: String, eraId: String, date: String, main: List<Int>, bonus: List<Int>) =
        CorpusDrawRecord(id, gameId, eraId, LocalDate.parse(date), null, "Draw", main, bonus,
            "source", "SOURCE_VALIDATED", "official", evidence.evidenceId, false)

    private fun validate(eras: List<GameEraRecord>, draw: CorpusDrawRecord) =
        CorpusContractValidator.validate(listOf(draw), eras, listOf(evidence), LocalDate.parse("2026-08-29"))
}

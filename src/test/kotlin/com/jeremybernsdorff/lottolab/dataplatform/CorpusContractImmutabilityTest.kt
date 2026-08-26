package com.jeremybernsdorff.lottolab.dataplatform

import com.jeremybernsdorff.lottolab.dataplatform.fingerprint.AnalyticalDatasetFingerprint
import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusManifest
import com.jeremybernsdorff.lottolab.dataplatform.model.DrawPublicMetadataRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.GameBundleDescriptor
import com.jeremybernsdorff.lottolab.dataplatform.model.GameEraRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.NumberSelectionRuleRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.PrizeTierRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.SourceEvidenceRecord
import com.jeremybernsdorff.lottolab.dataplatform.validation.CorpusContractValidator
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CorpusContractImmutabilityTest {
    private fun draw(
        id: String = "draw-1",
        date: String = "2025-01-01",
        session: String? = null,
        main: List<Int> = listOf(1, 2, 3),
        bonus: List<Int> = listOf(9),
        sample: Boolean = false
    ) = CorpusDrawRecord(
        id, "game", "era", LocalDate.parse(date), null, session, main, bonus,
        "source-ref", "SOURCE_VALIDATED", "official", "evidence-1", sample
    )

    @Test
    fun corpusDrawRecordDefensivelyCopiesAndExposesUnmodifiableNumberLists() {
        val callerMain = mutableListOf(1, 2, 3)
        val callerBonus = mutableListOf(9)
        val record = draw(main = callerMain, bonus = callerBonus)
        val fingerprint = AnalyticalDatasetFingerprint.compute(listOf(record))

        callerMain.add(4)
        callerBonus.clear()

        assertEquals(listOf(1, 2, 3), record.mainValues)
        assertEquals(listOf(9), record.bonusValues)
        assertEquals(fingerprint, AnalyticalDatasetFingerprint.compute(listOf(record)))
        assertFailsWith<UnsupportedOperationException> { (record.mainValues as MutableList<Int>).add(4) }
        assertFailsWith<UnsupportedOperationException> { (record.bonusValues as MutableList<Int>).clear() }
    }

    @Test
    fun corpusDrawRecordCopyAlsoDefensivelySnapshotsReplacementLists() {
        val original = draw()
        val replacementMain = mutableListOf(3, 2, 1)
        val replacementBonus = mutableListOf(8)
        val copied = original.copy(mainValues = replacementMain, bonusValues = replacementBonus)

        replacementMain.clear()
        replacementBonus.add(7)

        assertEquals(listOf(3, 2, 1), copied.mainValues)
        assertEquals(listOf(8), copied.bonusValues)
        assertFailsWith<UnsupportedOperationException> { (copied.mainValues as MutableList<Int>).clear() }
        assertFailsWith<UnsupportedOperationException> { (copied.bonusValues as MutableList<Int>).add(7) }
        val equivalent = original.copy(mainValues = listOf(3, 2, 1), bonusValues = listOf(8))
        assertEquals(equivalent, copied)
        assertEquals(equivalent.hashCode(), copied.hashCode())
    }

    @Test
    fun metadataAndManifestCollectionsAreDefensiveUnmodifiableSnapshots() {
        val tier = PrizeTierRecord("tier-1", "Match", 1, 100, "OFFICIAL_VERIFIED")
        val callerTiers = mutableListOf(tier)
        val metadata = DrawPublicMetadataRecord("draw-1", 1_000, 1, false, null, null, callerTiers)
        callerTiers.clear()
        assertEquals(listOf(tier), metadata.prizeTiers)
        assertFailsWith<UnsupportedOperationException> { (metadata.prizeTiers as MutableList<PrizeTierRecord>).clear() }
        val equivalentMetadata = DrawPublicMetadataRecord("draw-1", 1_000, 1, false, null, null, listOf(tier))
        assertEquals(equivalentMetadata, metadata)
        assertEquals(equivalentMetadata.hashCode(), metadata.hashCode())

        val bundle = GameBundleDescriptor("game", "v1", 1, LocalDate.parse("2025-01-01"),
            LocalDate.parse("2025-01-01"), "a".repeat(64), "b".repeat(64), null)
        val callerBundles = mutableListOf(bundle)
        val generated = Instant.parse("2025-01-02T00:00:00Z")
        val manifest = CorpusManifest(1, "v1", generated, callerBundles)
        callerBundles.clear()
        assertEquals(listOf(bundle), manifest.bundles)
        assertFailsWith<UnsupportedOperationException> { (manifest.bundles as MutableList<GameBundleDescriptor>).clear() }
        val equivalentManifest = CorpusManifest(1, "v1", generated, listOf(bundle))
        assertEquals(equivalentManifest, manifest)
        assertEquals(equivalentManifest.hashCode(), manifest.hashCode())
    }

    @Test
    fun validatorResultCollectionsAreUnmodifiableSnapshots() {
        val callerMain = mutableListOf(3, 1, 2)
        val trusted = draw(id = "a", main = callerMain)
        val duplicate = draw(id = "b")
        val conflictOne = draw(id = "c", date = "2025-01-02", main = listOf(1, 2, 3))
        val conflictTwo = draw(id = "d", date = "2025-01-02", main = listOf(1, 2, 4))
        val rejected = draw(id = "e", date = "2025-01-03", sample = true)
        val rule = NumberSelectionRuleRecord(3, 1, 9, true, false, 1, 1, 9, false, false)
        val era = GameEraRecord("game", "era", LocalDate.parse("2020-01-01"), null, rule)
        val evidence = SourceEvidenceRecord("evidence-1", "source-1", "Official", "https://example.test/data",
            null, Instant.parse("2025-01-01T00:00:00Z"), "a".repeat(64), "1", null)
        val result = CorpusContractValidator.validate(
            listOf(trusted, duplicate, conflictOne, conflictTwo, rejected), listOf(era), listOf(evidence),
            LocalDate.parse("2025-12-31")
        )

        callerMain.clear()
        assertEquals(listOf(1, 2, 3), result.trustedRecords.single().mainValues)
        assertFailsWith<UnsupportedOperationException> { (result.trustedRecords as MutableList<CorpusDrawRecord>).add(trusted) }
        assertFailsWith<UnsupportedOperationException> { (result.duplicateEvidenceRecords as MutableList<CorpusDrawRecord>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.conflictedRecords as MutableList<CorpusDrawRecord>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.rejectedRecords as MutableList<CorpusDrawRecord>).clear() }
    }
}

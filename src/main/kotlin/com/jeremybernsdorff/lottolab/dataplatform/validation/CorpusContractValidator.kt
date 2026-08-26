package com.jeremybernsdorff.lottolab.dataplatform.validation

import com.jeremybernsdorff.lottolab.dataplatform.fingerprint.AnalyticalDatasetFingerprint.normalizedSession
import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.GameEraRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.SourceEvidenceRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.immutableListCopy
import java.time.LocalDate
import java.util.Locale

object CorpusContractValidator {
    private val acceptedStatuses = setOf("SOURCE_VALIDATED", "OFFICIAL_VERIFIED")

    class Result(
        trustedRecords: List<CorpusDrawRecord>,
        duplicateEvidenceRecords: List<CorpusDrawRecord>,
        conflictedRecords: List<CorpusDrawRecord>,
        rejectedRecords: List<CorpusDrawRecord>
    ) {
        val trustedRecords = immutableListCopy(trustedRecords)
        val duplicateEvidenceRecords = immutableListCopy(duplicateEvidenceRecords)
        val conflictedRecords = immutableListCopy(conflictedRecords)
        val rejectedRecords = immutableListCopy(rejectedRecords)
    }

    fun validate(
        records: List<CorpusDrawRecord>,
        eras: List<GameEraRecord>,
        evidence: List<SourceEvidenceRecord>,
        validationDate: LocalDate
    ): Result {
        val evidenceIds = evidence.map { it.evidenceId }.toSet()
        val valid = mutableListOf<CorpusDrawRecord>()
        val rejected = mutableListOf<CorpusDrawRecord>()

        records.forEach { record ->
            val resolved = eras.filter { it.gameId == record.gameId && contains(it, record.drawDate) }
            val status = record.validationStatus.trim().uppercase(Locale.US)
            val rule = resolved.singleOrNull()?.rule
            val identityPresent = listOf(
                record.stableId, record.gameId, record.eraId, record.sourceReference,
                record.origin, record.sourceEvidenceId
            ).all { it.isNotBlank() }
            val mainValid = rule != null &&
                record.mainValues.size == rule.mainNumberCount &&
                record.mainValues.all { it in rule.mainMinimum..rule.mainMaximum } &&
                (!rule.mainNumbersUnique || record.mainValues.distinct().size == record.mainValues.size)
            val bonusValid = rule != null &&
                record.bonusValues.size == rule.bonusNumberCount &&
                (rule.bonusNumberCount == 0 || record.bonusValues.all { it in rule.bonusMinimum!!..rule.bonusMaximum!! }) &&
                (rule.bonusMayRepeat || record.bonusValues.distinct().size == record.bonusValues.size) &&
                (rule.bonusMayOverlapMain || record.bonusValues.none(record.mainValues::contains))
            if (!identityPresent || record.isSample || status !in acceptedStatuses ||
                record.sourceEvidenceId !in evidenceIds || record.drawDate > validationDate ||
                resolved.size != 1 || resolved.singleOrNull()?.eraId != record.eraId || !mainValid || !bonusValid
            ) {
                rejected += record
            } else {
                valid += record.copy(
                    validationStatus = status,
                    drawSession = normalizedSession(record.drawSession).ifEmpty { null },
                    mainValues = if (rule!!.orderMatters) record.mainValues.toList() else record.mainValues.sorted(),
                    bonusValues = record.bonusValues.toList()
                )
            }
        }

        val trusted = mutableListOf<CorpusDrawRecord>()
        val duplicates = mutableListOf<CorpusDrawRecord>()
        val conflicts = mutableListOf<CorpusDrawRecord>()
        valid.groupBy { Triple(it.gameId, it.drawDate, normalizedSession(it.drawSession)) }.values.forEach { group ->
            val results = group.groupBy { it.mainValues to it.bonusValues }
            if (results.size > 1) {
                conflicts += group.sortedBy { it.stableId }
            } else {
                val ordered = group.sortedBy { it.stableId }
                trusted += ordered.first()
                duplicates += ordered.drop(1)
            }
        }
        return Result(trusted.sortedBy { it.stableId }, duplicates, conflicts, rejected)
    }

    private fun contains(era: GameEraRecord, date: LocalDate): Boolean =
        (era.effectiveFrom == null || date >= era.effectiveFrom) &&
            (era.effectiveUntil == null || date < era.effectiveUntil)
}

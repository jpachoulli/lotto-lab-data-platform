package com.jeremybernsdorff.lottolab.dataplatform.model

import java.time.Instant
import java.time.LocalDate
import java.util.Collections

internal fun <T> immutableListCopy(source: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

data class NumberSelectionRuleRecord(
    val mainNumberCount: Int,
    val mainMinimum: Int,
    val mainMaximum: Int,
    val mainNumbersUnique: Boolean,
    val orderMatters: Boolean,
    val bonusNumberCount: Int,
    val bonusMinimum: Int?,
    val bonusMaximum: Int?,
    val bonusMayRepeat: Boolean,
    val bonusMayOverlapMain: Boolean
) {
    init {
        require(mainNumberCount > 0)
        require(mainMinimum <= mainMaximum)
        require(bonusNumberCount >= 0)
        require((bonusMinimum == null) == (bonusMaximum == null))
        require(bonusNumberCount == 0 || bonusMinimum != null)
        require(bonusMinimum == null || bonusMinimum <= bonusMaximum!!)
    }
}

data class GameEraRecord(
    val gameId: String,
    val eraId: String,
    val effectiveFrom: LocalDate?,
    val effectiveUntil: LocalDate?,
    val rule: NumberSelectionRuleRecord
) {
    init {
        require(gameId.isNotBlank() && eraId.isNotBlank())
        require(effectiveFrom == null || effectiveUntil == null || effectiveFrom < effectiveUntil)
    }
}

data class SourceEvidenceRecord(
    val evidenceId: String,
    val sourceId: String,
    val sourceOrganization: String,
    val canonicalSourceUrl: String,
    val sourceDatasetId: String?,
    val retrievedAtUtc: Instant,
    val rawSha256: String,
    val parserVersion: String,
    val termsReference: String?
) {
    init {
        require(listOf(evidenceId, sourceId, sourceOrganization, canonicalSourceUrl, parserVersion).all { it.isNotBlank() })
        require(rawSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

class CorpusDrawRecord(
    val stableId: String,
    val gameId: String,
    val eraId: String,
    val drawDate: LocalDate,
    val drawTimestampMillis: Long?,
    val drawSession: String?,
    mainValues: List<Int>,
    bonusValues: List<Int>,
    val sourceReference: String,
    val validationStatus: String,
    val origin: String,
    val sourceEvidenceId: String,
    val isSample: Boolean
) {
    val mainValues: List<Int> = immutableListCopy(mainValues)
    val bonusValues: List<Int> = immutableListCopy(bonusValues)

    fun copy(
        stableId: String = this.stableId,
        gameId: String = this.gameId,
        eraId: String = this.eraId,
        drawDate: LocalDate = this.drawDate,
        drawTimestampMillis: Long? = this.drawTimestampMillis,
        drawSession: String? = this.drawSession,
        mainValues: List<Int> = this.mainValues,
        bonusValues: List<Int> = this.bonusValues,
        sourceReference: String = this.sourceReference,
        validationStatus: String = this.validationStatus,
        origin: String = this.origin,
        sourceEvidenceId: String = this.sourceEvidenceId,
        isSample: Boolean = this.isSample
    ) = CorpusDrawRecord(stableId, gameId, eraId, drawDate, drawTimestampMillis, drawSession, mainValues,
        bonusValues, sourceReference, validationStatus, origin, sourceEvidenceId, isSample)

    override fun equals(other: Any?): Boolean = other is CorpusDrawRecord &&
        stableId == other.stableId && gameId == other.gameId && eraId == other.eraId &&
        drawDate == other.drawDate && drawTimestampMillis == other.drawTimestampMillis &&
        drawSession == other.drawSession && mainValues == other.mainValues && bonusValues == other.bonusValues &&
        sourceReference == other.sourceReference && validationStatus == other.validationStatus &&
        origin == other.origin && sourceEvidenceId == other.sourceEvidenceId && isSample == other.isSample

    override fun hashCode(): Int {
        var result = stableId.hashCode()
        result = 31 * result + gameId.hashCode()
        result = 31 * result + eraId.hashCode()
        result = 31 * result + drawDate.hashCode()
        result = 31 * result + (drawTimestampMillis?.hashCode() ?: 0)
        result = 31 * result + (drawSession?.hashCode() ?: 0)
        result = 31 * result + mainValues.hashCode()
        result = 31 * result + bonusValues.hashCode()
        result = 31 * result + sourceReference.hashCode()
        result = 31 * result + validationStatus.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + sourceEvidenceId.hashCode()
        result = 31 * result + isSample.hashCode()
        return result
    }

    override fun toString(): String =
        "CorpusDrawRecord(stableId=$stableId, gameId=$gameId, eraId=$eraId, drawDate=$drawDate, " +
            "drawTimestampMillis=$drawTimestampMillis, drawSession=$drawSession, mainValues=$mainValues, " +
            "bonusValues=$bonusValues, sourceReference=$sourceReference, validationStatus=$validationStatus, " +
            "origin=$origin, sourceEvidenceId=$sourceEvidenceId, isSample=$isSample)"
}

data class PrizeTierRecord(
    val tierId: String,
    val description: String,
    val winnerCount: Long?,
    val prizeAmountMinorUnits: Long?,
    val status: String
)

class DrawPublicMetadataRecord(
    val stableDrawId: String,
    val advertisedJackpotMinorUnits: Long?,
    val jackpotWinnerCount: Long?,
    val rollover: Boolean?,
    val multiplier: String?,
    val extraDrawData: String?,
    prizeTiers: List<PrizeTierRecord>
) {
    val prizeTiers: List<PrizeTierRecord> = immutableListCopy(prizeTiers)

    override fun equals(other: Any?): Boolean = other is DrawPublicMetadataRecord &&
        stableDrawId == other.stableDrawId && advertisedJackpotMinorUnits == other.advertisedJackpotMinorUnits &&
        jackpotWinnerCount == other.jackpotWinnerCount && rollover == other.rollover &&
        multiplier == other.multiplier && extraDrawData == other.extraDrawData && prizeTiers == other.prizeTiers

    override fun hashCode(): Int {
        var result = stableDrawId.hashCode()
        result = 31 * result + (advertisedJackpotMinorUnits?.hashCode() ?: 0)
        result = 31 * result + (jackpotWinnerCount?.hashCode() ?: 0)
        result = 31 * result + (rollover?.hashCode() ?: 0)
        result = 31 * result + (multiplier?.hashCode() ?: 0)
        result = 31 * result + (extraDrawData?.hashCode() ?: 0)
        result = 31 * result + prizeTiers.hashCode()
        return result
    }

    override fun toString(): String =
        "DrawPublicMetadataRecord(stableDrawId=$stableDrawId, advertisedJackpotMinorUnits=$advertisedJackpotMinorUnits, " +
            "jackpotWinnerCount=$jackpotWinnerCount, rollover=$rollover, multiplier=$multiplier, " +
            "extraDrawData=$extraDrawData, prizeTiers=$prizeTiers)"
}

data class CoverageRecord(
    val gameId: String,
    val eraId: String,
    val earliestKnownDraw: LocalDate?,
    val latestKnownDraw: LocalDate?,
    val expectedDrawCount: Long?,
    val validatedDrawCount: Long,
    val missingDrawCount: Long?,
    val conflictingDrawCount: Long,
    val coveragePercent: Double?,
    val sourceStatus: String,
    val automationStatus: String
)

data class GameBundleDescriptor(
    val gameId: String,
    val corpusVersion: String,
    val recordCount: Long,
    val earliestDrawDate: LocalDate?,
    val latestDrawDate: LocalDate?,
    val analyticalFingerprint: String,
    val bundleSha256: String,
    val evidenceBundleSha256: String?
)

class CorpusManifest(
    val schemaVersion: Int,
    val corpusVersion: String,
    val generatedAtUtc: Instant,
    bundles: List<GameBundleDescriptor>
) {
    val bundles: List<GameBundleDescriptor> = immutableListCopy(bundles)

    override fun equals(other: Any?): Boolean = other is CorpusManifest &&
        schemaVersion == other.schemaVersion && corpusVersion == other.corpusVersion &&
        generatedAtUtc == other.generatedAtUtc && bundles == other.bundles

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + corpusVersion.hashCode()
        result = 31 * result + generatedAtUtc.hashCode()
        result = 31 * result + bundles.hashCode()
        return result
    }

    override fun toString(): String =
        "CorpusManifest(schemaVersion=$schemaVersion, corpusVersion=$corpusVersion, " +
            "generatedAtUtc=$generatedAtUtc, bundles=$bundles)"
}

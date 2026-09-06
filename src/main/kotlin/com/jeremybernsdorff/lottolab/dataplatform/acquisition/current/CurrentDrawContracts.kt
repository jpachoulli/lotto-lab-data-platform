package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import java.time.Instant
import java.time.LocalDate

private val SHA256 = Regex("[0-9a-f]{64}")

class OfficialDrawObservation(
    val gameId: String,
    val drawDate: LocalDate,
    mainValues: List<Int>,
    bonusValues: List<Int>,
    val sourceId: String,
    val sourceOrganization: String,
    val sourceUrl: String,
    val retrievedAtUtc: Instant,
    val rawSha256: String,
    drawMetadata: Map<String, String> = emptyMap()
) {
    val mainValues: List<Int> = mainValues.toList()
    val bonusValues: List<Int> = bonusValues.toList()
    val drawMetadata: Map<String, String> = drawMetadata.toMap()
    init {
        require(gameId.isNotBlank())
        require(sourceId.isNotBlank())
        require(sourceOrganization.isNotBlank())
        require(sourceUrl.startsWith("https://"))
        require(SHA256.matches(rawSha256))
    }

    override fun equals(other: Any?): Boolean = other is OfficialDrawObservation &&
        gameId == other.gameId && drawDate == other.drawDate && mainValues == other.mainValues &&
        bonusValues == other.bonusValues && sourceId == other.sourceId && sourceOrganization == other.sourceOrganization &&
        sourceUrl == other.sourceUrl && retrievedAtUtc == other.retrievedAtUtc && rawSha256 == other.rawSha256 &&
        drawMetadata == other.drawMetadata
    override fun hashCode(): Int = listOf(gameId, drawDate, mainValues, bonusValues, sourceId, sourceOrganization, sourceUrl, retrievedAtUtc, rawSha256, drawMetadata).hashCode()
}

class VerifiedCurrentDraw(
    val stableId: String,
    val gameId: String,
    val eraId: String,
    val drawDate: LocalDate,
    mainValues: List<Int>,
    bonusValues: List<Int>,
    observations: List<OfficialDrawObservation>
) {
    val mainValues: List<Int> = mainValues.toList()
    val bonusValues: List<Int> = bonusValues.toList()
    val observations: List<OfficialDrawObservation> = observations.toList()

    init {
        require(gameId.isNotBlank())
        require(eraId.isNotBlank())
        require(stableId == "$gameId-$drawDate")
        require(this.observations.size == 2)
        require(this.observations.map { it.sourceId }.distinct().size == 2)
        require(this.observations.all { observation ->
            observation.gameId == gameId &&
                observation.drawDate == drawDate &&
                observation.mainValues == this.mainValues &&
                observation.bonusValues == this.bonusValues
        })
    }

    fun toCorpusDrawRecord(sourceEvidenceId: String, sourceReference: String): CorpusDrawRecord =
        CorpusDrawRecord(
            stableId = stableId, gameId = gameId, eraId = eraId, drawDate = drawDate,
            drawTimestampMillis = null, drawSession = "DRAW", mainValues = mainValues,
            bonusValues = bonusValues, sourceReference = sourceReference,
            validationStatus = "OFFICIAL_VERIFIED", origin = "DUAL_OFFICIAL_CURRENT_ACQUISITION",
            sourceEvidenceId = sourceEvidenceId, isSample = false
        )

    override fun equals(other: Any?): Boolean = other is VerifiedCurrentDraw &&
        stableId == other.stableId && gameId == other.gameId && eraId == other.eraId && drawDate == other.drawDate &&
        mainValues == other.mainValues && bonusValues == other.bonusValues && observations == other.observations
    override fun hashCode(): Int = listOf(stableId, gameId, eraId, drawDate, mainValues, bonusValues, observations).hashCode()
}

/* The immutable public properties above are defensive snapshots. */
/*
 * Kept as ordinary classes rather than data classes because Kotlin data-class
 * constructor properties cannot defensively copy caller-owned collections.
 */
/*
 * The following declarations are intentionally not duplicated; this comment
 * marks the contract boundary for source reviewers.
 */

sealed interface OfficialDrawSourceResult {
    data class Found(val observation: OfficialDrawObservation) : OfficialDrawSourceResult
    data object NotYetPublished : OfficialDrawSourceResult
    data class Unavailable(val reason: String) : OfficialDrawSourceResult {
        init { require(reason.isNotBlank()) }
    }
}

interface OfficialDrawSource {
    val gameId: String
    val sourceId: String
    fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant): OfficialDrawSourceResult
}

sealed interface CurrentDrawAcquisitionResult {
    data class Verified(val draw: VerifiedCurrentDraw) : CurrentDrawAcquisitionResult
    data class AwaitingSecondSource(val availableObservation: OfficialDrawObservation) : CurrentDrawAcquisitionResult
    data object NotYetAvailable : CurrentDrawAcquisitionResult
    data class Conflict(
        val first: OfficialDrawObservation,
        val second: OfficialDrawObservation,
        val reason: String
    ) : CurrentDrawAcquisitionResult
    data class Unavailable(val reason: String) : CurrentDrawAcquisitionResult {
        init { require(reason.isNotBlank()) }
    }
}


internal fun validateCurrentNumbers(
    gameId: String,
    date: LocalDate,
    main: List<Int>,
    bonus: List<Int>
): String? {
    val eras = NationalGameEraCatalog.resolve(gameId, date)
    val era = eras.singleOrNull() ?: return "expected exactly one era for $gameId on $date"
    val rule = era.drawResultRule
    if (main.size != rule.mainNumberCount) return "invalid main number count"
    if (rule.mainNumbersUnique && main.distinct().size != main.size) return "duplicate main number"
    if (main.any { it !in rule.mainMinimum..rule.mainMaximum }) return "main number out of range"
    if (bonus.size != rule.bonusNumberCountMinimum || bonus.size != rule.bonusNumberCountMaximum) return "invalid bonus number count"
    if (bonus.any { it !in rule.bonusMinimum!!..rule.bonusMaximum!! }) return "bonus number out of range"
    if (!rule.bonusMayRepeat && bonus.distinct().size != bonus.size) return "duplicate bonus number"
    if (!rule.bonusMayOverlapMain && bonus.any(main::contains)) return "bonus overlaps main"
    return null
}

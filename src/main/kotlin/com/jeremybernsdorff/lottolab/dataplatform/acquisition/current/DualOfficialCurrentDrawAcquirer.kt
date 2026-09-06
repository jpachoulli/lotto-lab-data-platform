package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import java.time.Instant
import java.time.LocalDate

class DualOfficialCurrentDrawAcquirer(
    private val registry: CurrentDrawSourceRegistry
) {
    fun acquire(gameId: String, drawDate: LocalDate, retrievedAtUtc: Instant): CurrentDrawAcquisitionResult {
        val sources = registry.sourcesFor(gameId)
        if (sources.size != 2) return CurrentDrawAcquisitionResult.Unavailable("unsupported game or source configuration: $gameId")
        val sourceResults = sources.map { source -> source to source.fetch(drawDate, retrievedAtUtc) }
        sourceResults.forEach { (source, result) ->
            if (result is OfficialDrawSourceResult.Found) {
                val observation = result.observation
                if (observation.sourceId != source.sourceId ||
                    observation.gameId != source.gameId ||
                    observation.gameId != gameId ||
                    observation.drawDate != drawDate
                ) {
                    return CurrentDrawAcquisitionResult.Unavailable(
                        "source observation identity mismatch for ${source.sourceId}"
                    )
                }
            }
        }
        val results = sourceResults.map { it.second }
        val found = results.filterIsInstance<OfficialDrawSourceResult.Found>().map { it.observation }
        if (results.all { it is OfficialDrawSourceResult.NotYetPublished }) return CurrentDrawAcquisitionResult.NotYetAvailable
        if (found.size == 1 && results.any { it is OfficialDrawSourceResult.NotYetPublished }) return CurrentDrawAcquisitionResult.AwaitingSecondSource(found.single())
        if (found.size == 2) {
            val first = found[0]; val second = found[1]
            if (first.mainValues != second.mainValues || first.bonusValues != second.bonusValues) {
                return CurrentDrawAcquisitionResult.Conflict(first, second, "official sources disagree")
            }
            val era = NationalGameEraCatalog.resolve(gameId, drawDate).singleOrNull()
                ?: return CurrentDrawAcquisitionResult.Unavailable("no unique era for $gameId on $drawDate")
            validateCurrentNumbers(gameId, drawDate, first.mainValues, first.bonusValues)?.let { return CurrentDrawAcquisitionResult.Unavailable(it) }
            return CurrentDrawAcquisitionResult.Verified(VerifiedCurrentDraw("$gameId-$drawDate", gameId, era.eraId, drawDate, first.mainValues, first.bonusValues, found))
        }
        return CurrentDrawAcquisitionResult.Unavailable(results.filterIsInstance<OfficialDrawSourceResult.Unavailable>().joinToString("; ") { it.reason }.ifBlank { "two-source verification unavailable" })
    }
}

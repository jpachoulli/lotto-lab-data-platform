package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate

object CurrentDrawAcquisitionCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: <gameId> <YYYY-MM-DD>" }
        val gameId = args[0]
        val date = LocalDate.parse(args[1])
        val result = DualOfficialCurrentDrawAcquirer(CurrentDrawSourceRegistry.production()).acquire(gameId, date, Instant.now())
        val output = linkedMapOf<String, Any?>("status" to result::class.simpleName, "gameId" to gameId, "drawDate" to date.toString())
        if (result is CurrentDrawAcquisitionResult.Verified) {
            output["status"] = "Verified"; output["stableId"] = result.draw.stableId; output["eraId"] = result.draw.eraId
            output["mainValues"] = result.draw.mainValues; output["bonusValues"] = result.draw.bonusValues
            output["sourceIds"] = result.draw.observations.map { it.sourceId }; output["sourceUrls"] = result.draw.observations.map { it.sourceUrl }
            output["rawSha256"] = result.draw.observations.map { it.rawSha256 }
        }
        println(ObjectMapper().writeValueAsString(output))
    }
}

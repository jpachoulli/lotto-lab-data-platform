package com.jeremybernsdorff.lottolab.dataplatform.catalog

import com.jeremybernsdorff.lottolab.dataplatform.model.DrawResultRuleRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.GameEraRecord
import com.jeremybernsdorff.lottolab.dataplatform.model.immutableListCopy
import java.time.LocalDate

object NationalGameEraCatalog {
    val eras: List<GameEraRecord> = immutableListCopy(
        listOf(
            era("powerball", "powerball_45_45_1992", "1992-04-22", "1997-11-05", 45, 45),
            era("powerball", "powerball_49_42_1997", "1997-11-05", "2002-10-09", 49, 42),
            era("powerball", "powerball_53_42_2002", "2002-10-09", "2005-08-31", 53, 42),
            era("powerball", "powerball_55_42_2005", "2005-08-31", "2009-01-07", 55, 42),
            era("powerball", "powerball_59_39_2009", "2009-01-07", "2012-01-18", 59, 39),
            era("powerball", "powerball_59_35_2012", "2012-01-18", "2015-10-07", 59, 35),
            era("powerball", "powerball_69_26_2015", "2015-10-07", null, 69, 26),
            era("mega_millions", "mega_millions_52_52_2002", "2002-05-17", "2005-06-24", 52, 52),
            era("mega_millions", "mega_millions_56_46_2005", "2005-06-24", "2013-10-22", 56, 46),
            era("mega_millions", "mega_millions_75_15_2013", "2013-10-22", "2017-10-31", 75, 15),
            era("mega_millions", "mega_millions_70_25_2017", "2017-10-31", "2025-04-08", 70, 25),
            era("mega_millions", "mega_millions_70_24_2025", "2025-04-08", null, 70, 24),
            era("lotto_america", "lotto_america_52_10_2017", "2017-11-15", null, 52, 10)
        )
    )

    fun forGame(gameId: String): List<GameEraRecord> = eras.filter { it.gameId == gameId }

    fun resolve(gameId: String, drawDate: LocalDate): List<GameEraRecord> = forGame(gameId).filter { era ->
        (era.effectiveFrom == null || drawDate >= era.effectiveFrom) &&
            (era.effectiveUntil == null || drawDate < era.effectiveUntil)
    }

    private fun era(
        gameId: String,
        eraId: String,
        effectiveFrom: String,
        effectiveUntil: String?,
        mainMaximum: Int,
        bonusMaximum: Int
    ) = GameEraRecord(
        gameId = gameId,
        eraId = eraId,
        effectiveFrom = LocalDate.parse(effectiveFrom),
        effectiveUntil = effectiveUntil?.let(LocalDate::parse),
        drawResultRule = DrawResultRuleRecord(
            mainNumberCount = 5,
            mainMinimum = 1,
            mainMaximum = mainMaximum,
            mainNumbersUnique = true,
            orderMatters = false,
            bonusNumberCountMinimum = 1,
            bonusNumberCountMaximum = 1,
            bonusMinimum = 1,
            bonusMaximum = bonusMaximum,
            bonusMayRepeat = false,
            bonusMayOverlapMain = true
        )
    )
}

package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import java.time.LocalDate

internal object CoreThreeVerifiedDrawValidator {

    private val expectedSourceIds =
        mapOf(
            "powerball" to
                setOf(
                    "ny_gaming_commission_powerball",
                    "delaware_lottery_powerball"
                ),

            "mega_millions" to
                setOf(
                    "ny_gaming_commission_mega_millions",
                    "delaware_lottery_mega_millions"
                ),

            "lotto_america" to
                setOf(
                    "musl_lotto_america",
                    "iowa_lottery_lotto_america"
                )
        )

    fun validate(
        draw: VerifiedCurrentDraw,
        expectedGameId: String,
        expectedDrawDate: LocalDate
    ) {
        val expectedSources =
            expectedSourceIds[expectedGameId]
                ?: throw IllegalArgumentException(
                    "UNSUPPORTED_VERIFIED_DRAW_GAME: $expectedGameId"
                )

        require(draw.gameId == expectedGameId) {
            "VERIFIED_DRAW_GAME_MISMATCH: " +
                "$expectedGameId/$expectedDrawDate"
        }

        require(draw.drawDate == expectedDrawDate) {
            "VERIFIED_DRAW_DATE_MISMATCH: " +
                "$expectedGameId/$expectedDrawDate"
        }

        require(
            draw.stableId ==
                "${draw.gameId}-${draw.drawDate}"
        ) {
            "VERIFIED_DRAW_STABLE_ID_MISMATCH: " +
                "$expectedGameId/$expectedDrawDate"
        }

        val eras =
            NationalGameEraCatalog.resolve(
                draw.gameId,
                draw.drawDate
            )

        require(eras.size == 1) {
            "VERIFIED_DRAW_ERA_RESOLUTION_MISMATCH: " +
                "${draw.gameId}/${draw.drawDate}"
        }

        val era =
            eras.single()

        require(draw.eraId == era.eraId) {
            "VERIFIED_DRAW_ERA_ID_MISMATCH: " +
                "${draw.gameId}/${draw.drawDate}"
        }

        CoreThreeDrawRuleValidator.validate(
            mainValues =
                draw.mainValues,
            bonusValues =
                draw.bonusValues,
            rule =
                era.drawResultRule,
            context =
                "${draw.gameId}/${draw.drawDate}"
        )

        require(draw.observations.size == 2) {
            "VERIFIED_DRAW_OBSERVATION_COUNT_MISMATCH: " +
                "${draw.gameId}/${draw.drawDate}"
        }

        val sourceIds =
            draw.observations.map {
                it.sourceId
            }

        require(
            sourceIds.distinct().size == 2
        ) {
            "VERIFIED_DRAW_DUPLICATE_SOURCE_ID: " +
                "${draw.gameId}/${draw.drawDate}"
        }

        require(
            sourceIds.toSet() == expectedSources
        ) {
            "VERIFIED_SOURCE_SET_MISMATCH: " +
                "${draw.gameId}/${draw.drawDate};" +
                "expected=${expectedSources.sorted()};" +
                "actual=${sourceIds.sorted()}"
        }

        val canonicalDrawMain =
            CoreThreeDrawRuleValidator
                .canonicalMainValues(
                    draw.mainValues,
                    era.drawResultRule
                )

        draw.observations.forEach {
            observation ->

            require(
                observation.gameId ==
                    draw.gameId
            ) {
                "OBSERVATION_GAME_MISMATCH: " +
                    "${draw.gameId}/${draw.drawDate}/" +
                    observation.sourceId
            }

            require(
                observation.drawDate ==
                    draw.drawDate
            ) {
                "OBSERVATION_DATE_MISMATCH: " +
                    "${draw.gameId}/${draw.drawDate}/" +
                    observation.sourceId
            }

            val canonicalObservationMain =
                CoreThreeDrawRuleValidator
                    .canonicalMainValues(
                        observation.mainValues,
                        era.drawResultRule
                    )

            require(
                canonicalObservationMain ==
                    canonicalDrawMain
            ) {
                "OBSERVATION_MAIN_RESULT_MISMATCH: " +
                    "${draw.gameId}/${draw.drawDate}/" +
                    observation.sourceId
            }

            require(
                observation.bonusValues ==
                    draw.bonusValues
            ) {
                "OBSERVATION_BONUS_RESULT_MISMATCH: " +
                    "${draw.gameId}/${draw.drawDate}/" +
                    observation.sourceId
            }
        }
    }
}

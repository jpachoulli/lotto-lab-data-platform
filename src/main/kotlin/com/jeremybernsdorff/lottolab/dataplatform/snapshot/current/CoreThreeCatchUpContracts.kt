package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.time.LocalDate

internal enum class GameCatchUpState { CURRENT, ADVANCED, WAITING_FOR_OFFICIAL_RESULT }

internal data class GameCatchUpSummary(
    val gameId: String,
    val baseCutoff: LocalDate,
    val matureThrough: LocalDate,
    val verifiedFrontier: LocalDate,
    val newlyWrittenLedgerDates: List<LocalDate>,
    val reusedLedgerDates: List<LocalDate>,
    val waitingOnDate: LocalDate?,
    val waitingReason: String?,
    val state: GameCatchUpState
) {
    init {
        require(gameId in CoreThreePermanentLedgerPolicy.games)
        require(verifiedFrontier >= baseCutoff)
        if (state == GameCatchUpState.WAITING_FOR_OFFICIAL_RESULT) {
            require(waitingOnDate != null)
            require(!waitingReason.isNullOrBlank())
        } else {
            require(waitingOnDate == null)
            require(waitingReason == null)
        }
    }
}

internal data class CoreThreeCatchUpSummary(
    val matureThrough: LocalDate,
    val games: Map<String, GameCatchUpSummary>
) {
    init { require(games.keys == CoreThreePermanentLedgerPolicy.games.toSet()) { "CORE_THREE_GAME_SET_MISMATCH" } }
    val candidateRequired get() = games.values.any { it.verifiedFrontier > it.baseCutoff }
    val newlyWrittenLedgerCount get() = games.values.sumOf { it.newlyWrittenLedgerDates.size }
    val reusedLedgerCount get() = games.values.sumOf { it.reusedLedgerDates.size }
    fun throughDates(): Map<String, LocalDate> = games.mapValues { it.value.verifiedFrontier }
}

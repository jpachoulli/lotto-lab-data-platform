package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.time.DayOfWeek
import java.time.LocalDate

object CoreThreeCurrentSchedule {
    private val days = mapOf(
        "powerball" to setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY),
        "mega_millions" to setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY),
        "lotto_america" to setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)
    )
    fun expectedDrawDates(gameId: String, afterExclusive: LocalDate, throughInclusive: LocalDate): List<LocalDate> {
        val allowed = days[gameId] ?: error("unsupported game: $gameId")
        return generateSequence(afterExclusive.plusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(throughInclusive) }
            .filter { it.dayOfWeek in allowed }.toList()
    }
}

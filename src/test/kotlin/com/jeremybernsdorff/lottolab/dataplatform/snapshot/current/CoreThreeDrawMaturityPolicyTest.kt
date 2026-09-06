package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreThreeDrawMaturityPolicyTest {
    @Test fun maturityIsPreviousEasternCalendarDay() {
        assertEquals(LocalDate.parse("2026-09-02"), CoreThreeDrawMaturityPolicy.matureThrough(Instant.parse("2026-09-03T16:00:00Z")))
    }

    @Test fun maturityUsesEasternTimezone() {
        assertEquals(LocalDate.parse("2026-09-01"), CoreThreeDrawMaturityPolicy.matureThrough(Instant.parse("2026-09-03T03:30:00Z")))
        assertEquals(LocalDate.parse("2026-09-03"), CoreThreeDrawMaturityPolicy.matureThrough(Instant.parse("2026-09-04T04:30:00Z")))
    }
}

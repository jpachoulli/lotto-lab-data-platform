package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreThreeCatchUpCliTest {
    @Test
    fun fixedDryRunMaturityMatchesCatchUpPolicy() {
        assertEquals(
            LocalDate.parse("2026-09-02"),
            CoreThreeDrawMaturityPolicy.matureThrough(Instant.parse("2026-09-03T16:00:00Z"))
        )
    }
}

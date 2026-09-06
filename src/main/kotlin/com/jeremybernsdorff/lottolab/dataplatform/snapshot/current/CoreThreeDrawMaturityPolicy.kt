package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object CoreThreeDrawMaturityPolicy {
    private val drawZone = ZoneId.of("America/New_York")

    fun matureThrough(nowUtc: Instant): LocalDate =
        nowUtc.atZone(drawZone).toLocalDate().minusDays(1)
}

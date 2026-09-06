package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreThreePermanentLedgerPolicyTest {
    @Test
    fun inceptionDatesAndPathsAreStable() {
        assertEquals(LocalDate.parse("2026-08-17"), CoreThreePermanentLedgerPolicy.inceptionDate("powerball"))
        assertEquals(
            Path.of("root", "mega_millions", "2026-09-01.json"),
            CoreThreePermanentLedgerPolicy.ledgerPath(Path.of("root"), "mega_millions", LocalDate.parse("2026-09-01"))
        )
    }
}

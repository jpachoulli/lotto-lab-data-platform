package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

object CoreThreeCatchUpCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4) { "expected: <descriptorPath> <ledgerRoot> <nowUtc> <outputJson>" }
        val summary = CoreThreeCatchUpService().catchUp(Path.of(args[0]), Path.of(args[1]), Instant.parse(args[2]))
        val root = linkedMapOf<String, Any?>("matureThrough" to summary.matureThrough.toString(), "candidateRequired" to summary.candidateRequired, "newlyWrittenLedgerCount" to summary.newlyWrittenLedgerCount, "reusedLedgerCount" to summary.reusedLedgerCount, "games" to summary.games.toSortedMap().mapValues { (_, g) -> linkedMapOf<String, Any?>("baseCutoff" to g.baseCutoff.toString(), "matureThrough" to g.matureThrough.toString(), "verifiedFrontier" to g.verifiedFrontier.toString(), "state" to g.state.name, "waitingOnDate" to g.waitingOnDate?.toString(), "waitingReason" to g.waitingReason, "newlyWrittenLedgerDates" to g.newlyWrittenLedgerDates.map(LocalDate::toString), "reusedLedgerDates" to g.reusedLedgerDates.map(LocalDate::toString)) })
        val json = B2_JSON.writeValueAsString(root) + "\n"; val output = Path.of(args[3]); output.toAbsolutePath().parent?.let(Files::createDirectories); Files.writeString(output, json); print(json)
    }
}

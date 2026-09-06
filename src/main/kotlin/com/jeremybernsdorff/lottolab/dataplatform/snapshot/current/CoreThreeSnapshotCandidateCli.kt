package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

object CoreThreeSnapshotCandidateCli {
    @JvmStatic fun main(args: Array<String>) {
        val through = LocalDate.parse(args.firstOrNull() ?: error("throughDate required"))
        val root = Path.of("build/b2-current-snapshot-candidate")
        val context = BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0000000000000000000000000000000000000000", 1, "CANDIDATE")
        val builder = IncrementalCoreThreeSnapshotBuilder()
        val first = builder.build(Path.of("data/distribution/core_three/latest.json"), through, context, root)
        val repeatRoot = root.resolve("repeat")
        Files.createDirectories(repeatRoot.resolve("ledger"))
        Files.walk(root.resolve("ledger")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { source ->
                val target = repeatRoot.resolve("ledger").resolve(root.resolve("ledger").relativize(source).toString())
                Files.createDirectories(target.parent)
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val second = builder.build(Path.of("data/distribution/core_three/latest.json"), through, context, repeatRoot)
        val a = Files.readAllBytes(root.resolve("artifact/${first.snapshotVersion}.zip")); val b = Files.readAllBytes(repeatRoot.resolve("artifact/${second.snapshotVersion}.zip"))
        require(a.contentEquals(b)) { "NON_DETERMINISTIC_ZIP" }
        println(B2_JSON.writeValueAsString(mapOf("status" to first.status, "snapshotVersion" to first.snapshotVersion, "newDrawCount" to first.newDrawCount, "counts" to first.counts, "memberCount" to first.memberCount, "bundleContentId" to first.bundleContentId, "manifestSha256" to first.manifestSha256, "archiveSha256" to first.archiveSha256, "archiveByteSize" to first.archiveByteSize, "buildTwiceByteEqual" to true, "sourceRepositoryCommit" to first.sourceRepositoryCommit, "publishable" to first.publishable)))
    }
}

package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path

internal object CoreThreeRepositoryTestFixtures {

    val augustDescriptor: Path =
        Path.of(
            "data/distribution/core_three/releases/" +
                "core-three-pb-2026-08-15-mm-2026-08-28-" +
                "la-2026-08-26-v1.json"
        )

    val permanentLedgerRoot: Path =
        Path.of(
            "data/current/core_three/verified"
        )

    val bootstrapRelativePaths: List<Path> =
        listOf(
            Path.of("powerball", "2026-08-17.json"),
            Path.of("powerball", "2026-08-19.json"),
            Path.of("powerball", "2026-08-22.json"),
            Path.of("powerball", "2026-08-24.json"),
            Path.of("powerball", "2026-08-26.json"),
            Path.of("powerball", "2026-08-29.json"),
            Path.of("powerball", "2026-08-31.json"),
            Path.of("powerball", "2026-09-02.json"),
            Path.of("mega_millions", "2026-09-01.json"),
            Path.of("lotto_america", "2026-08-29.json"),
            Path.of("lotto_america", "2026-08-31.json"),
            Path.of("lotto_america", "2026-09-02.json")
        )

    fun bootstrapLedgerFiles(
        root: Path = permanentLedgerRoot
    ): List<Path> =
        bootstrapRelativePaths.map { relative ->
            root.resolve(relative)
        }

    fun requireBootstrapLedgerPresent(
        root: Path = permanentLedgerRoot
    ): List<Path> {
        val files =
            bootstrapLedgerFiles(root)

        require(
            files.size == 12
        ) {
            "BOOTSTRAP_LEDGER_FIXTURE_COUNT_MISMATCH"
        }

        val missing =
            files.filterNot { path ->
                Files.isRegularFile(path)
            }

        require(
            missing.isEmpty()
        ) {
            "MISSING_ACCEPTED_BOOTSTRAP_LEDGER: " +
                missing.joinToString(",") { path ->
                    root.relativize(path)
                        .toString()
                        .replace('\\', '/')
                }
        }

        return files
    }

    fun allPermanentLedgerFiles(
        root: Path = permanentLedgerRoot
    ): List<Path> {
        if (!Files.exists(root)) {
            return emptyList()
        }

        return Files.walk(root).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .sorted()
                .toList()
        }
    }

    fun materializeBootstrapLedger(
        targetRoot: Path
    ) {
        requireBootstrapLedgerPresent()
            .forEach { source ->
                val relative =
                    permanentLedgerRoot.relativize(
                        source
                    )

                val draw =
                    VerifiedCurrentDrawLedger.read(
                        Files.readAllBytes(
                            source
                        )
                    )

                val result =
                    VerifiedCurrentDrawLedger.writeImmutable(
                        targetRoot.resolve(
                            relative
                        ),
                        draw
                    )

                require(
                    result == "WRITTEN"
                ) {
                    "BOOTSTRAP_FIXTURE_WRITE_FAILED: $relative"
                }
            }

        require(
            allPermanentLedgerFiles(
                targetRoot
            ).size == 12
        ) {
            "BOOTSTRAP_FIXTURE_MATERIALIZED_COUNT_MISMATCH"
        }
    }
}

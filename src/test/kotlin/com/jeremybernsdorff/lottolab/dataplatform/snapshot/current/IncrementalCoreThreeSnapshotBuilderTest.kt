package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.OfficialDrawObservation
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.nio.file.Files
import java.nio.file.Path
import java.util.TimeZone

class IncrementalCoreThreeSnapshotBuilderTest {
    @Test fun currentScheduleProducesExactPowerballGap() {
        assertEquals(listOf("2026-08-17", "2026-08-19", "2026-08-22", "2026-08-24", "2026-08-26", "2026-08-29", "2026-08-31", "2026-09-02"), CoreThreeCurrentSchedule.expectedDrawDates("powerball", LocalDate.parse("2026-08-15"), LocalDate.parse("2026-09-02")).map(LocalDate::toString))
    }

    @Test fun currentScheduleProducesExactMegaMillionsGap() {
        assertEquals(listOf("2026-09-01"), CoreThreeCurrentSchedule.expectedDrawDates("mega_millions", LocalDate.parse("2026-08-28"), LocalDate.parse("2026-09-02")).map(LocalDate::toString))
    }

    @Test fun currentScheduleProducesExactLottoAmericaGap() {
        assertEquals(listOf("2026-08-29", "2026-08-31", "2026-09-02"), CoreThreeCurrentSchedule.expectedDrawDates("lotto_america", LocalDate.parse("2026-08-26"), LocalDate.parse("2026-09-02")).map(LocalDate::toString))
    }

    @Test fun ledgerRoundTripPreservesVerifiedCurrentDraw() {
        val d = draw()
        val first = VerifiedCurrentDrawLedger.bytes(d)
        val roundTrip = VerifiedCurrentDrawLedger.read(first)
        assertEquals(d.stableId, roundTrip.stableId)
        assertEquals(d.gameId, roundTrip.gameId)
        assertEquals(d.drawDate, roundTrip.drawDate)
        assertEquals(d.mainValues, roundTrip.mainValues)
        assertEquals(d.bonusValues, roundTrip.bonusValues)
        assertEquals(d.observations.toSet(), roundTrip.observations.toSet())
    }

    @Test fun ledgerSerializationIsDeterministic() {
        val d = draw()
        assertContentEquals(VerifiedCurrentDrawLedger.bytes(d), VerifiedCurrentDrawLedger.bytes(VerifiedCurrentDrawLedger.read(VerifiedCurrentDrawLedger.bytes(d))))
    }

    @Test fun observationEvidenceFingerprintGoldenVector() {
        val original = draw()
        val changed = original.observations.mapIndexed { index, o ->
            if (index == 0) OfficialDrawObservation(o.gameId, o.drawDate, o.mainValues, o.bonusValues, o.sourceId, o.sourceOrganization, o.sourceUrl, o.retrievedAtUtc, o.rawSha256, mapOf("quote" to "a\"b", "slash" to "c\\d")) else o
        }
        val d = VerifiedCurrentDraw(original.stableId, original.gameId, original.eraId, original.drawDate, original.mainValues, original.bonusValues, changed)
        val evidence = canonicalObservationEvidence(d, "ny_gaming_commission_powerball")
        val metadata = evidence.lineSequence().single { it.startsWith("metadata=") }.removePrefix("metadata=")
        val node = B2_JSON.readTree(metadata)
        assertEquals("a\"b", node["quote"].asText())
        assertEquals("c\\d", node["slash"].asText())
    }

    @Test fun candidateZeroCommitAllowed() {
        BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE")
    }

    @Test fun differentExistingLedgerBytesConflict() {
        val dir = Files.createTempDirectory("ledger-conflict")
        try {
            val path = dir.resolve("powerball/2026-09-02.json")
            val first = draw()
            val secondObservation = first.observations.mapIndexed { index, o ->
                if (index == 0) OfficialDrawObservation(o.gameId, o.drawDate, o.mainValues, o.bonusValues, o.sourceId, o.sourceOrganization, o.sourceUrl, o.retrievedAtUtc, "c".repeat(64), o.drawMetadata) else o
            }
            val second = VerifiedCurrentDraw(first.stableId, first.gameId, first.eraId, first.drawDate, first.mainValues, first.bonusValues, secondObservation)
            assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(path, first))
            assertFailsWith<IllegalStateException> { VerifiedCurrentDrawLedger.writeImmutable(path, second) }
        } finally { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test fun alreadyMaterializedLedgerDrawIsIgnored() {
        val dir = Files.createTempDirectory("ledger-present")
        try {
            val path = dir.resolve("powerball/2026-09-02.json")
            val d = draw()
            assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(path, d))
            assertEquals("ALREADY_PRESENT", VerifiedCurrentDrawLedger.writeImmutable(path, d))
            val persisted = VerifiedCurrentDrawLedger.read(Files.readAllBytes(path))
            assertEquals(d.stableId, persisted.stableId)
            assertEquals(d.mainValues, persisted.mainValues)
        } finally { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test fun newlyAcquiredDrawIsReadBackFromPersistedLedger() {
        val dir = Files.createTempDirectory("ledger-readback")
        try {
            val d = draw(); val path = dir.resolve("powerball/2026-09-02.json")
            VerifiedCurrentDrawLedger.writeImmutable(path, d)
            val persisted = VerifiedCurrentDrawLedger.read(Files.readAllBytes(path))
            assertEquals(d.stableId, persisted.stableId)
            assertEquals(d.observations.map { it.sourceId }.sorted(), persisted.observations.map { it.sourceId }.sorted())
        } finally { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test fun offSchedulePendingDrawFails() {
        assertEquals(false, CoreThreeCurrentSchedule.expectedDrawDates("powerball", LocalDate.parse("2026-08-15"), LocalDate.parse("2026-08-16")).contains(LocalDate.parse("2026-08-16")))
    }

    @Test fun eraCatalogHasExactly15Records() {
        assertEquals(15, com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog.eras.size)
    }

    @Test fun buildContextRejectsPublishableZeroCommit() {
        assertFailsWith<IllegalArgumentException> { BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "PUBLISHABLE") }
    }

    @Test fun bundleContentIdGoldenVectorIsStable() {
        val members = mapOf("a" to "one".toByteArray(), "b" to "two".toByteArray())
        val input = members.toSortedMap().entries.joinToString("") { "${it.key}\u0000${sha256(it.value)}\n" }.toByteArray()
        assertEquals("5be2f52e14cb5c50d8628d06f32decd859a0ba109a2f1225b15cd339dfcda1a8", sha256(input))
    }

    @Test fun productionBuilderNeverAcquiresMissingDraw() {
        val output = Files.createTempDirectory("ledger-only-builder")
        try {
            val builder = IncrementalCoreThreeSnapshotBuilder(acquirer = { _, _, _ -> error("ACQUIRER_MUST_NOT_BE_CALLED") })
            val summary = builder.buildFromVerifiedLedger(
                descriptor,
                mapOf("powerball" to LocalDate.parse("2026-09-02"), "mega_millions" to LocalDate.parse("2026-09-01"), "lotto_america" to LocalDate.parse("2026-09-02")),
                BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE"),
                output,
                Path.of("data/current/core_three/verified")
            )
            assertEquals("BUILT", summary.status)
            assertEquals("9b812294b5a065ec7b867cb14f1d47d7923301e719394bf4f09fbf45b4414791", summary.archiveSha256)
            assertEquals(433104, summary.archiveByteSize)
        } finally { Files.walk(output).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test fun productionBuilderPublishableContextProducesPublishableArtifact() {
        val output = Files.createTempDirectory("ledger-only-publishable")
        try {
            val commit = "1".repeat(40)
            val summary = IncrementalCoreThreeSnapshotBuilder(acquirer = { _, _, _ -> error("ACQUIRER_MUST_NOT_BE_CALLED") })
                .buildFromVerifiedLedger(descriptor, fullThroughDates(), BuildContext(Instant.parse("2026-09-05T15:00:00Z"), commit, 1, "PUBLISHABLE"), output, Path.of("data/current/core_three/verified"))
            assertEquals("BUILT", summary.status)
            assertTrue(summary.publishable)
            assertEquals(commit, summary.sourceRepositoryCommit)
            val metadata = B2_JSON.readTree(Files.readAllBytes(output.resolve("artifact/BUILD_METADATA.json")))
            val manifest = B2_JSON.readTree(Files.readAllBytes(output.resolve("artifact/manifest.json")))
            assertTrue(metadata["publishable"].asBoolean())
            assertEquals(commit, metadata["sourceRepositoryCommit"].asText())
            assertEquals(commit, manifest["sourceRepositoryCommit"].asText())
        } finally { deleteTree(output) }
    }

    @Test fun productionBuilderMissingVerifiedLedgerFailsWithoutAcquisition() {
        val output = Files.createTempDirectory("ledger-only-missing")
        val ledger = Files.createTempDirectory("ledger-only-incomplete")
        var calls = 0
        try {
            val failure = assertFailsWith<IllegalStateException> {
                IncrementalCoreThreeSnapshotBuilder(acquirer = { _, _, _ -> calls++; error("ACQUIRER_MUST_NOT_BE_CALLED") })
                    .buildFromVerifiedLedger(descriptor, fullThroughDates(), BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE"), output, ledger)
            }
            assertTrue(failure.message.orEmpty().contains("MISSING_VERIFIED_LEDGER_DRAW"))
            assertEquals(0, calls)
            assertFalse(Files.exists(ledger.resolve("powerball/2026-08-17.json")))
        } finally { deleteTree(output); deleteTree(ledger) }
    }

    @Test fun builderRejectsThroughDateBeforeBaseCutoff() {
        val output = Files.createTempDirectory("builder-before-cutoff")
        try {
            val failure = assertFailsWith<IllegalArgumentException> {
                IncrementalCoreThreeSnapshotBuilder(acquirer = { _, _, _ -> error("ACQUIRER_MUST_NOT_BE_CALLED") })
                    .buildFromVerifiedLedger(descriptor, fullThroughDates() + ("powerball" to LocalDate.parse("2026-08-14")), BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE"), output, Path.of("data/current/core_three/verified"))
            }
            assertTrue(failure.message.orEmpty().contains("THROUGH_DATE_BEFORE_BASE_CUTOFF"))
            assertFalse(Files.exists(output.resolve("artifact")))
        } finally { deleteTree(output) }
    }

    @Test fun productionBuilderNoChangeCreatesNoArtifact() {
        val output = Files.createTempDirectory("builder-no-change")
        val ledger = Files.createTempDirectory("builder-no-change-ledger")
        var calls = 0
        try {
            val summary = IncrementalCoreThreeSnapshotBuilder(acquirer = { _, _, _ -> calls++; error("ACQUIRER_MUST_NOT_BE_CALLED") })
                .buildFromVerifiedLedger(descriptor, baseCutoffs(), BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE"), output, ledger)
            assertEquals("NO_CHANGE", summary.status)
            assertEquals(0, summary.newDrawCount)
            assertEquals(0, summary.memberCount)
            assertFalse(summary.publishable)
            assertEquals(0, calls)
            assertFalse(Files.exists(output.resolve("artifact")))
        } finally { deleteTree(output); deleteTree(ledger) }
    }

    @Test fun productionBuilderCanonicalComparisonIgnoresUnorderedMainInputOrder() {
        val output = Files.createTempDirectory("builder-unordered-canonical")
        val ledger = Files.createTempDirectory("builder-unordered-ledger")
        try {
            copyLedger(Path.of("data/current/core_three/verified"), ledger)
            val rows = StrictCsvCodec.parse(PublishedCoreThreeSnapshotReader().read(descriptor).members.getValue("draws/powerball.csv").toString(Charsets.UTF_8))
            val row = rows.drop(1).maxBy { it[3] }
            val ordered = row.subList(6, 11).map(String::toInt)
            val permuted = listOf(ordered[4], ordered[1], ordered[0], ordered[3], ordered[2])
            assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(ledger.resolve("powerball/${row[3]}.json"), draw("powerball", LocalDate.parse(row[3]), permuted, row[11].toInt())))
            val summary = IncrementalCoreThreeSnapshotBuilder(acquirer = { _, _, _ -> error("ACQUIRER_MUST_NOT_BE_CALLED") })
                .buildFromVerifiedLedger(descriptor, fullThroughDates(), BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE"), output, ledger)
            assertEquals("BUILT", summary.status)
        } finally { deleteTree(output); deleteTree(ledger) }
    }

    @Test fun legacySingleThroughDateBuildStillPasses() {
        val output = Files.createTempDirectory("builder-legacy")
        var calls = 0
        try {
            val summary = IncrementalCoreThreeSnapshotBuilder(acquirer = { game, date, _ -> calls++; draw(game, date) })
                .build(descriptor, LocalDate.parse("2026-09-02"), BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE"), output)
            assertEquals("BUILT", summary.status)
            assertEquals(12, calls)
        } finally { deleteTree(output) }
    }

    @Test fun permanentLedgerRebuildRemainsAcceptedB2Bytes() {
        val output = Files.createTempDirectory("builder-b2-identity")
        try {
            val summary = IncrementalCoreThreeSnapshotBuilder(acquirer = { _, _, _ -> error("ACQUIRER_MUST_NOT_BE_CALLED") })
                .buildFromVerifiedLedger(descriptor, fullThroughDates(), BuildContext(Instant.parse("2026-09-05T15:00:00Z"), "0".repeat(40), 1, "CANDIDATE"), output, Path.of("data/current/core_three/verified"))
            assertEquals("9b812294b5a065ec7b867cb14f1d47d7923301e719394bf4f09fbf45b4414791", summary.archiveSha256)
            assertEquals(433104L, summary.archiveByteSize)
            assertEquals("723c59e70dbe0274a685d731423ca262c347ff4f20f8a8babc9f5f6dd7e0aad9", summary.manifestSha256)
            assertEquals("f1c4ecdeb07fb7cfe03cb11a0bccc6c0a6d73420accd17393aed9e4e1676b957", summary.bundleContentId)
            val generated = Files.readAllBytes(output.resolve("artifact/${summary.snapshotVersion}.zip"))
            val accepted = Files.readAllBytes(Path.of("src/test/resources/snapshot/current/accepted-candidate.zip"))
            assertContentEquals(accepted, generated)
        } finally { deleteTree(output) }
    }

    @Test
    fun sourceCatalogManifestHashIsStableAcrossLfAndCrlfCheckouts() {
        val raw =
            Files.readAllBytes(
                Path.of(
                    "src/main/kotlin/com/jeremybernsdorff/" +
                        "lottolab/dataplatform/catalog/" +
                        "NationalGameEraCatalog.kt"
                )
            )

        val lfText =
            raw.toString(
                Charsets.UTF_8
            )
                .replace(
                    "\r\n",
                    "\n"
                )

        require(
            '\r' !in lfText
        )

        val lf =
            lfText.toByteArray(
                Charsets.UTF_8
            )

        val crlf =
            lfText
                .replace(
                    "\n",
                    "\r\n"
                )
                .toByteArray(
                    Charsets.UTF_8
                )

        val expected =
            "86ccdfb8b31f1bd5508554b5ed70a9a47b78410e0189e78757f9930568972c84"

        assertContentEquals(
            canonicalSourceCatalogKotlinBytes(
                lf
            ),
            canonicalSourceCatalogKotlinBytes(
                crlf
            )
        )

        assertEquals(
            expected,
            sha256(
                canonicalSourceCatalogKotlinBytes(
                    lf
                )
            )
        )

        assertEquals(
            expected,
            sha256(
                canonicalSourceCatalogKotlinBytes(
                    crlf
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            canonicalSourceCatalogKotlinBytes(
                "a\rb".toByteArray(
                    Charsets.UTF_8
                )
            )
        }
    }

    @Test
    fun deterministicZipEntryEpochRepresentsCanonicalLocalMidnight() {
        assertEquals(
            315532800000L,
            deterministicZipEntryEpochMillis(
                ZoneId.of(
                    "UTC"
                )
            )
        )

        assertEquals(
            315558000000L,
            deterministicZipEntryEpochMillis(
                ZoneId.of(
                    "America/Denver"
                )
            )
        )
    }

    @Test
    fun acceptedCandidateBytesRemainExactUnderUtcDefaultTimezone() {
        val originalTimeZone =
            TimeZone.getDefault()

        val output =
            Files.createTempDirectory(
                "builder-b2-utc"
            )

        try {
            TimeZone.setDefault(
                TimeZone.getTimeZone(
                    "UTC"
                )
            )

            val summary =
                IncrementalCoreThreeSnapshotBuilder(
                    acquirer = { _, _, _ ->
                        error(
                            "ACQUIRER_MUST_NOT_BE_CALLED"
                        )
                    }
                ).buildFromVerifiedLedger(
                    descriptor,
                    fullThroughDates(),
                    BuildContext(
                        Instant.parse(
                            "2026-09-05T15:00:00Z"
                        ),
                        "0".repeat(
                            40
                        ),
                        1,
                        "CANDIDATE"
                    ),
                    output,
                    Path.of(
                        "data/current/core_three/verified"
                    )
                )

            assertEquals(
                "9b812294b5a065ec7b867cb14f1d47d7923301e719394bf4f09fbf45b4414791",
                summary.archiveSha256
            )

            assertEquals(
                433104L,
                summary.archiveByteSize
            )

            assertEquals(
                "723c59e70dbe0274a685d731423ca262c347ff4f20f8a8babc9f5f6dd7e0aad9",
                summary.manifestSha256
            )

            assertEquals(
                "f1c4ecdeb07fb7cfe03cb11a0bccc6c0a6d73420accd17393aed9e4e1676b957",
                summary.bundleContentId
            )

            val generated =
                Files.readAllBytes(
                    output.resolve(
                        "artifact/${summary.snapshotVersion}.zip"
                    )
                )

            val accepted =
                Files.readAllBytes(
                    Path.of(
                        "src/test/resources/snapshot/current/" +
                            "accepted-candidate.zip"
                    )
                )

            assertContentEquals(
                accepted,
                generated
            )
        } finally {
            TimeZone.setDefault(
                originalTimeZone
            )

            deleteTree(
                output
            )
        }
    }

    private fun fullThroughDates() = mapOf("powerball" to LocalDate.parse("2026-09-02"), "mega_millions" to LocalDate.parse("2026-09-01"), "lotto_america" to LocalDate.parse("2026-09-02"))
    private fun baseCutoffs() = mapOf("powerball" to LocalDate.parse("2026-08-15"), "mega_millions" to LocalDate.parse("2026-08-28"), "lotto_america" to LocalDate.parse("2026-08-26"))
    private fun copyLedger(source: Path, target: Path) { Files.walk(source).use { stream -> stream.filter(Files::isRegularFile).forEach { file -> val relative = source.relativize(file); val destination = target.resolve(relative.toString()); Files.createDirectories(destination.parent); Files.copy(file, destination) } } }
    private fun deleteTree(root: Path) { if (Files.exists(root)) Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }

    private fun draw(game: String = "powerball", date: LocalDate = LocalDate.parse("2026-09-02"), main: List<Int>? = null, bonus: Int? = null): VerifiedCurrentDraw {
        val values = main ?: when (game) { "powerball" -> listOf(3, 10, 29, 58, 64); "mega_millions" -> listOf(1, 22, 51, 61, 63); else -> listOf(2, 4, 16, 39, 45) }
        val bonusValue = bonus ?: when (game) { "powerball" -> 14; "mega_millions" -> 17; else -> 6 }
        val sourceIds = when (game) { "powerball" -> listOf("ny_gaming_commission_powerball", "delaware_lottery_powerball"); "mega_millions" -> listOf("ny_gaming_commission_mega_millions", "delaware_lottery_mega_millions"); else -> listOf("musl_lotto_america", "iowa_lottery_lotto_america") }
        val observations = sourceIds.mapIndexed { index, sourceId -> OfficialDrawObservation(game, date, values, listOf(bonusValue), sourceId, if (index == 0) "New York State Gaming Commission" else "Delaware Lottery", "https://example.test/$sourceId", Instant.parse("2026-09-05T15:00:00Z"), (if (index == 0) "a" else "b").repeat(64)) }
        val era = com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog.resolve(game, date).single()
        return VerifiedCurrentDraw("$game-$date", game, era.eraId, date, values, listOf(bonusValue), observations)
    }

    private val descriptor: Path = Path.of("data/distribution/core_three/latest.json")
}

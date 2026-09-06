package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreThreePublishableSnapshotPreparationTest {
    private val sourceCommit =
        "1".repeat(
            40
        )

    private val snapshotVersion =
        "core-three-pb-2026-09-02-mm-2026-09-01-la-2026-09-02-v1"

    private val assetSha =
        "a".repeat(
            64
        )

    private val bundleId =
        "b".repeat(
            64
        )

    private val manifestSha =
        "c".repeat(
            64
        )

    private val recordCounts =
        mapOf(
            "powerball" to
                3850,
            "mega_millions" to
                3056,
            "lotto_america" to
                1135
        )

    private val throughDates =
        mapOf(
            "powerball" to
                LocalDate.parse(
                    "2026-09-02"
                ),
            "mega_millions" to
                LocalDate.parse(
                    "2026-09-01"
                ),
            "lotto_america" to
                LocalDate.parse(
                    "2026-09-02"
                )
        )

    @Test
    fun noChangeSummaryDoesNotInvokeBuilderOrCreateOutput() {
        val temp =
            Files.createTempDirectory(
                "publishable-no-change"
            )

        val catchUp =
            temp.resolve(
                "catch-up.json"
            )

        val output =
            temp.resolve(
                "output"
            )

        Files.write(
            catchUp,
            noChangeCatchUpBytes()
        )

        var builderCalled =
            false

        val result =
            prepareCoreThreePublishableSnapshot(
                descriptorPath =
                    temp.resolve(
                        "descriptor.json"
                    ),
                catchUpSummaryPath =
                    catchUp,
                ledgerRoot =
                    temp.resolve(
                        "ledger"
                    ),
                sourceRepositoryCommit =
                    sourceCommit,
                createdAtUtc =
                    Instant.parse(
                        "2026-09-06T18:00:00Z"
                    ),
                outputRoot =
                    output,
                build = {
                        _,
                        _,
                        _,
                        _,
                        _ ->
                    builderCalled =
                        true

                    error(
                        "BUILDER_MUST_NOT_BE_CALLED"
                    )
                }
            )

        assertEquals(
            "NO_CHANGE",
            result.status
        )

        assertEquals(
            null,
            result.snapshotVersion
        )

        assertEquals(
            null,
            result.artifactDirectory
        )

        assertEquals(
            false,
            builderCalled
        )

        assertTrue(
            Files.notExists(
                output
            )
        )
    }

    @Test
    fun candidateSummaryPassesExactFrontiersIntoPublishableLedgerBuild() {
        val temp =
            Files.createTempDirectory(
                "publishable-built"
            )

        val catchUp =
            temp.resolve(
                "catch-up.json"
            )

        val descriptor =
            temp.resolve(
                "descriptor.json"
            )

        val ledger =
            temp.resolve(
                "ledger"
            )

        val output =
            temp.resolve(
                "output"
            )

        Files.write(
            catchUp,
            advancedCatchUpBytes()
        )

        var builderCalled =
            false

        val result =
            prepareCoreThreePublishableSnapshot(
                descriptorPath =
                    descriptor,
                catchUpSummaryPath =
                    catchUp,
                ledgerRoot =
                    ledger,
                sourceRepositoryCommit =
                    sourceCommit,
                createdAtUtc =
                    Instant.parse(
                        "2026-09-06T18:00:00Z"
                    ),
                outputRoot =
                    output,
                build = {
                        actualDescriptor,
                        actualThroughDates,
                        context,
                        actualOutput,
                        actualLedger ->
                    builderCalled =
                        true

                    assertEquals(
                        descriptor,
                        actualDescriptor
                    )

                    assertEquals(
                        throughDates,
                        actualThroughDates
                    )

                    assertEquals(
                        sourceCommit,
                        context.sourceRepositoryCommit
                    )

                    assertEquals(
                        1,
                        context.revision
                    )

                    assertEquals(
                        "PUBLISHABLE",
                        context.buildMode
                    )

                    assertEquals(
                        output,
                        actualOutput
                    )

                    assertEquals(
                        ledger,
                        actualLedger
                    )

                    writeBuildMetadata(
                        output.resolve(
                            "artifact"
                        ),
                        sourceCommit
                    )

                    builtSummary(
                        sourceCommit
                    )
                }
            )

        assertTrue(
            builderCalled
        )

        assertEquals(
            "BUILT",
            result.status
        )

        assertEquals(
            snapshotVersion,
            result.snapshotVersion
        )

        assertEquals(
            throughDates,
            result.throughDates
        )

        assertEquals(
            output.resolve(
                "artifact"
            ),
            result.artifactDirectory
        )
    }

    @Test
    fun buildMetadataSourceCommitMismatchFailsClosed() {
        val temp =
            Files.createTempDirectory(
                "publishable-commit-mismatch"
            )

        val catchUp =
            temp.resolve(
                "catch-up.json"
            )

        val output =
            temp.resolve(
                "output"
            )

        Files.write(
            catchUp,
            advancedCatchUpBytes()
        )

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                prepareCoreThreePublishableSnapshot(
                    descriptorPath =
                        temp.resolve(
                            "descriptor.json"
                        ),
                    catchUpSummaryPath =
                        catchUp,
                    ledgerRoot =
                        temp.resolve(
                            "ledger"
                        ),
                    sourceRepositoryCommit =
                        sourceCommit,
                    createdAtUtc =
                        Instant.parse(
                            "2026-09-06T18:00:00Z"
                        ),
                    outputRoot =
                        output,
                    build = {
                            _,
                            _,
                            _,
                            _,
                            _ ->
                        writeBuildMetadata(
                            output.resolve(
                                "artifact"
                            ),
                            "2".repeat(
                                40
                            )
                        )

                        builtSummary(
                            sourceCommit
                        )
                    }
                )
            }

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "BUILD_METADATA_SOURCE_COMMIT_MISMATCH"
                )
        )
    }

    @Test
    fun nonEmptyOutputRootFailsBeforeBuilderInvocation() {
        val temp =
            Files.createTempDirectory(
                "publishable-stale-output"
            )

        val catchUp =
            temp.resolve(
                "catch-up.json"
            )

        val output =
            temp.resolve(
                "output"
            )

        Files.write(
            catchUp,
            advancedCatchUpBytes()
        )

        Files.createDirectories(
            output
        )

        Files.writeString(
            output.resolve(
                "stale.txt"
            ),
            "stale"
        )

        var builderCalled =
            false

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                prepareCoreThreePublishableSnapshot(
                    descriptorPath =
                        temp.resolve(
                            "descriptor.json"
                        ),
                    catchUpSummaryPath =
                        catchUp,
                    ledgerRoot =
                        temp.resolve(
                            "ledger"
                        ),
                    sourceRepositoryCommit =
                        sourceCommit,
                    createdAtUtc =
                        Instant.parse(
                            "2026-09-06T18:00:00Z"
                        ),
                    outputRoot =
                        output,
                    build = {
                            _,
                            _,
                            _,
                            _,
                            _ ->
                        builderCalled =
                            true

                        error(
                            "BUILDER_MUST_NOT_BE_CALLED"
                        )
                    }
                )
            }

        assertEquals(
            false,
            builderCalled
        )

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "PUBLICATION_OUTPUT_ROOT_NOT_EMPTY"
                )
        )
    }

    private fun builtSummary(
        commit: String
    ): CandidateBuildSummary =
        CandidateBuildSummary(
            status =
                "BUILT",
            snapshotVersion =
                snapshotVersion,
            newDrawCount =
                12,
            counts =
                recordCounts,
            memberCount =
                13,
            bundleContentId =
                bundleId,
            manifestSha256 =
                manifestSha,
            archiveSha256 =
                assetSha,
            archiveByteSize =
                433104L,
            publishable =
                true,
            sourceRepositoryCommit =
                commit
        )

    private fun writeBuildMetadata(
        artifactDirectory: Path,
        commit: String
    ) {
        Files.createDirectories(
            artifactDirectory
        )

        val metadata =
            linkedMapOf<String, Any>(
                "snapshotVersion" to
                    snapshotVersion,
                "assetSha256" to
                    assetSha,
                "assetByteSize" to
                    433104L,
                "bundleContentId" to
                    bundleId,
                "snapshotManifestSha256" to
                    manifestSha,
                "recordCounts" to
                    recordCounts,
                "cutoffs" to
                    throughDates.mapValues {
                        (_, date) ->
                        date.toString()
                    },
                "buildMode" to
                    "PUBLISHABLE",
                "sourceRepositoryCommit" to
                    commit,
                "publishable" to
                    true
            )

        Files.writeString(
            artifactDirectory.resolve(
                "BUILD_METADATA.json"
            ),
            B2_JSON.writeValueAsString(
                metadata
            ) +
                "\n"
        )
    }

    private fun advancedCatchUpBytes(): ByteArray =
        catchUpBytes(
            matureThrough =
                "2026-09-02",
            candidateRequired =
                true,
            reusedLedgerCount =
                12,
            games =
                linkedMapOf(
                    "powerball" to
                        gameSummary(
                            baseCutoff =
                                "2026-08-15",
                            matureThrough =
                                "2026-09-02",
                            frontier =
                                "2026-09-02",
                            state =
                                "ADVANCED",
                            reused =
                                listOf(
                                    "2026-08-17",
                                    "2026-08-19",
                                    "2026-08-22",
                                    "2026-08-24",
                                    "2026-08-26",
                                    "2026-08-29",
                                    "2026-08-31",
                                    "2026-09-02"
                                )
                        ),
                    "mega_millions" to
                        gameSummary(
                            baseCutoff =
                                "2026-08-28",
                            matureThrough =
                                "2026-09-02",
                            frontier =
                                "2026-09-01",
                            state =
                                "ADVANCED",
                            reused =
                                listOf(
                                    "2026-09-01"
                                )
                        ),
                    "lotto_america" to
                        gameSummary(
                            baseCutoff =
                                "2026-08-26",
                            matureThrough =
                                "2026-09-02",
                            frontier =
                                "2026-09-02",
                            state =
                                "ADVANCED",
                            reused =
                                listOf(
                                    "2026-08-29",
                                    "2026-08-31",
                                    "2026-09-02"
                                )
                        )
                )
        )

    private fun noChangeCatchUpBytes(): ByteArray =
        catchUpBytes(
            matureThrough =
                "2026-08-15",
            candidateRequired =
                false,
            reusedLedgerCount =
                0,
            games =
                linkedMapOf(
                    "powerball" to
                        gameSummary(
                            baseCutoff =
                                "2026-08-15",
                            matureThrough =
                                "2026-08-15",
                            frontier =
                                "2026-08-15",
                            state =
                                "CURRENT",
                            reused =
                                emptyList()
                        ),
                    "mega_millions" to
                        gameSummary(
                            baseCutoff =
                                "2026-08-28",
                            matureThrough =
                                "2026-08-15",
                            frontier =
                                "2026-08-28",
                            state =
                                "CURRENT",
                            reused =
                                emptyList()
                        ),
                    "lotto_america" to
                        gameSummary(
                            baseCutoff =
                                "2026-08-26",
                            matureThrough =
                                "2026-08-15",
                            frontier =
                                "2026-08-26",
                            state =
                                "CURRENT",
                            reused =
                                emptyList()
                        )
                )
        )

    private fun catchUpBytes(
        matureThrough: String,
        candidateRequired: Boolean,
        reusedLedgerCount: Int,
        games: Map<String, Map<String, Any?>>
    ): ByteArray =
        (
            B2_JSON.writeValueAsString(
                linkedMapOf(
                    "matureThrough" to
                        matureThrough,
                    "candidateRequired" to
                        candidateRequired,
                    "newlyWrittenLedgerCount" to
                        0,
                    "reusedLedgerCount" to
                        reusedLedgerCount,
                    "games" to
                        games
                )
            ) +
                "\n"
            ).toByteArray()

    private fun gameSummary(
        baseCutoff: String,
        matureThrough: String,
        frontier: String,
        state: String,
        reused: List<String>
    ): Map<String, Any?> =
        linkedMapOf(
            "baseCutoff" to
                baseCutoff,
            "matureThrough" to
                matureThrough,
            "verifiedFrontier" to
                frontier,
            "state" to
                state,
            "waitingOnDate" to
                null,
            "waitingReason" to
                null,
            "newlyWrittenLedgerDates" to
                emptyList<String>(),
            "reusedLedgerDates" to
                reused
        )
}

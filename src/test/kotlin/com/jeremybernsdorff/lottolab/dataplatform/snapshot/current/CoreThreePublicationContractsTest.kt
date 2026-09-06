package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreThreePublicationContractsTest {
    @Test
    fun acceptedCatchUpSummaryParsesExactFrontiersAndCounts() {
        val parsed =
            parseCoreThreePublicationCatchUpSummary(
                advancedCatchUpBytes()
            )

        assertTrue(
            parsed.candidateRequired
        )

        assertEquals(
            0,
            parsed.newlyWrittenLedgerCount
        )

        assertEquals(
            12,
            parsed.reusedLedgerCount
        )

        assertEquals(
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
            ),
            parsed.throughDates
        )
    }

    @Test
    fun catchUpSummaryRejectsCandidateRequiredMismatch() {
        val root =
            B2_JSON
                .readTree(
                    advancedCatchUpBytes()
                )
                .deepCopy<ObjectNode>()

        root.put(
            "candidateRequired",
            false
        )

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                parseCoreThreePublicationCatchUpSummary(
                    B2_JSON.writeValueAsBytes(
                        root
                    )
                )
            }

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "CANDIDATE_REQUIRED_MISMATCH"
                )
        )
    }

    @Test
    fun catchUpSummaryRejectsUnknownRootField() {
        val root =
            B2_JSON
                .readTree(
                    advancedCatchUpBytes()
                )
                .deepCopy<ObjectNode>()

        root.put(
            "unexpected",
            "x"
        )

        assertFailsWith<
            IllegalArgumentException
        > {
            parseCoreThreePublicationCatchUpSummary(
                B2_JSON.writeValueAsBytes(
                    root
                )
            )
        }
    }

    @Test
    fun catchUpSummaryRejectsIncompleteProcessedPrefix() {
        val root =
            B2_JSON
                .readTree(
                    advancedCatchUpBytes()
                )
                .deepCopy<ObjectNode>()

        val games =
            root["games"]
                as ObjectNode

        val powerball =
            games["powerball"]
                as ObjectNode

        powerball.set<JsonNode>(
            "reusedLedgerDates",
            B2_JSON.valueToTree(
                listOf(
                    "2026-09-02"
                )
            )
        )

        root.put(
            "reusedLedgerCount",
            5
        )

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                parseCoreThreePublicationCatchUpSummary(
                    B2_JSON.writeValueAsBytes(
                        root
                    )
                )
            }

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "CATCH_UP_PROCESSED_LEDGER_PREFIX_MISMATCH"
                )
        )
    }

    @Test
    fun catchUpSummaryRejectsCurrentStateThatSkippedMatureDraws() {
        val root =
            B2_JSON
                .readTree(
                    advancedCatchUpBytes()
                )
                .deepCopy<ObjectNode>()

        val games =
            root["games"]
                as ObjectNode

        val lottoAmerica =
            games["lotto_america"]
                as ObjectNode

        lottoAmerica.put(
            "verifiedFrontier",
            "2026-08-26"
        )

        lottoAmerica.put(
            "state",
            "CURRENT"
        )

        lottoAmerica.set<JsonNode>(
            "reusedLedgerDates",
            B2_JSON.valueToTree(
                emptyList<String>()
            )
        )

        root.put(
            "reusedLedgerCount",
            9
        )

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                parseCoreThreePublicationCatchUpSummary(
                    B2_JSON.writeValueAsBytes(
                        root
                    )
                )
            }

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "CURRENT_MATURE_DRAW_SKIPPED"
                )
        )
    }

    @Test
    fun catchUpSummaryRejectsOffScheduleAdvancedFrontier() {
        val root =
            B2_JSON
                .readTree(
                    advancedCatchUpBytes()
                )
                .deepCopy<ObjectNode>()

        val games =
            root["games"]
                as ObjectNode

        val megaMillions =
            games["mega_millions"]
                as ObjectNode

        megaMillions.put(
            "verifiedFrontier",
            "2026-08-29"
        )

        megaMillions.set<JsonNode>(
            "reusedLedgerDates",
            B2_JSON.valueToTree(
                emptyList<String>()
            )
        )

        root.put(
            "reusedLedgerCount",
            11
        )

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                parseCoreThreePublicationCatchUpSummary(
                    B2_JSON.writeValueAsBytes(
                        root
                    )
                )
            }

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "CATCH_UP_FRONTIER_OFF_SCHEDULE"
                )
        )
    }

    @Test
    fun publishableBuildMetadataParsesStrictIdentity() {
        val parsed =
            parseCoreThreePublicationBuildMetadata(
                buildMetadataBytes()
            )

        assertEquals(
            "core-three-pb-2026-09-02-mm-2026-09-01-la-2026-09-02-v1",
            parsed.snapshotVersion
        )

        assertEquals(
            "1".repeat(
                40
            ),
            parsed.sourceRepositoryCommit
        )

        assertEquals(
            433104L,
            parsed.assetByteSize
        )

        assertEquals(
            LocalDate.parse(
                "2026-09-02"
            ),
            parsed.cutoffs
                .getValue(
                    "powerball"
                )
        )
    }

    @Test
    fun buildMetadataRejectsZeroCommit() {
        val root =
            B2_JSON
                .readTree(
                    buildMetadataBytes()
                )
                .deepCopy<ObjectNode>()

        root.put(
            "sourceRepositoryCommit",
            "0".repeat(
                40
            )
        )

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                parseCoreThreePublicationBuildMetadata(
                    B2_JSON.writeValueAsBytes(
                        root
                    )
                )
            }

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "INVALID_PUBLICATION_SOURCE_COMMIT"
                )
        )
    }

    @Test
    fun buildMetadataRejectsSnapshotCutoffMismatch() {
        val root =
            B2_JSON
                .readTree(
                    buildMetadataBytes()
                )
                .deepCopy<ObjectNode>()

        root.put(
            "snapshotVersion",
            "core-three-pb-2026-09-01-mm-2026-09-01-la-2026-09-02-v1"
        )

        val failure =
            assertFailsWith<
                IllegalArgumentException
            > {
                parseCoreThreePublicationBuildMetadata(
                    B2_JSON.writeValueAsBytes(
                        root
                    )
                )
            }

        assertTrue(
            failure
                .message
                .orEmpty()
                .contains(
                    "BUILD_METADATA_SNAPSHOT_CUTOFF_MISMATCH"
                )
        )
    }

    private fun advancedCatchUpBytes(): ByteArray {
        val games =
            linkedMapOf<String, Any?>(
                "powerball" to
                    gameSummary(
                        "2026-08-15",
                        "2026-09-02",
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
                        "2026-08-28",
                        "2026-09-01",
                        listOf(
                            "2026-09-01"
                        )
                    ),
                "lotto_america" to
                    gameSummary(
                        "2026-08-26",
                        "2026-09-02",
                        listOf(
                            "2026-08-29",
                            "2026-08-31",
                            "2026-09-02"
                        )
                    )
            )

        return (
            B2_JSON.writeValueAsString(
                linkedMapOf(
                    "matureThrough" to
                        "2026-09-02",
                    "candidateRequired" to
                        true,
                    "newlyWrittenLedgerCount" to
                        0,
                    "reusedLedgerCount" to
                        12,
                    "games" to
                        games
                )
            ) +
                "\n"
            ).toByteArray()
    }

    private fun gameSummary(
        baseCutoff: String,
        frontier: String,
        reused: List<String>
    ): Map<String, Any?> =
        linkedMapOf(
            "baseCutoff" to
                baseCutoff,
            "matureThrough" to
                "2026-09-02",
            "verifiedFrontier" to
                frontier,
            "state" to
                "ADVANCED",
            "waitingOnDate" to
                null,
            "waitingReason" to
                null,
            "newlyWrittenLedgerDates" to
                emptyList<String>(),
            "reusedLedgerDates" to
                reused
        )

    private fun buildMetadataBytes(): ByteArray =
        (
            B2_JSON.writeValueAsString(
                linkedMapOf(
                    "snapshotVersion" to
                        "core-three-pb-2026-09-02-mm-2026-09-01-la-2026-09-02-v1",
                    "assetSha256" to
                        "9b812294b5a065ec7b867cb14f1d47d7923301e719394bf4f09fbf45b4414791",
                    "assetByteSize" to
                        433104,
                    "bundleContentId" to
                        "f1c4ecdeb07fb7cfe03cb11a0bccc6c0a6d73420accd17393aed9e4e1676b957",
                    "snapshotManifestSha256" to
                        "723c59e70dbe0274a685d731423ca262c347ff4f20f8a8babc9f5f6dd7e0aad9",
                    "recordCounts" to
                        mapOf(
                            "powerball" to
                                3850,
                            "mega_millions" to
                                3056,
                            "lotto_america" to
                                1135
                        ),
                    "cutoffs" to
                        mapOf(
                            "powerball" to
                                "2026-09-02",
                            "mega_millions" to
                                "2026-09-01",
                            "lotto_america" to
                                "2026-09-02"
                        ),
                    "buildMode" to
                        "PUBLISHABLE",
                    "sourceRepositoryCommit" to
                        "1".repeat(
                            40
                        ),
                    "publishable" to
                        true
                )
            ) +
                "\n"
            ).toByteArray()
}

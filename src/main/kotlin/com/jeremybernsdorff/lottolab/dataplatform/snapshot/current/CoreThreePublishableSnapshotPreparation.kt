package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

internal typealias CoreThreePublishableSnapshotBuild =
    (
        Path,
        Map<String, LocalDate>,
        BuildContext,
        Path,
        Path
    ) -> CandidateBuildSummary

internal data class CoreThreePublishableSnapshotPreparationResult(
    val status: String,
    val snapshotVersion: String?,
    val throughDates: Map<String, LocalDate>,
    val sourceRepositoryCommit: String,
    val artifactDirectory: Path?
)

internal fun prepareCoreThreePublishableSnapshot(
    descriptorPath: Path,
    catchUpSummaryPath: Path,
    ledgerRoot: Path,
    sourceRepositoryCommit: String,
    createdAtUtc: Instant,
    outputRoot: Path,
    build: CoreThreePublishableSnapshotBuild = {
            descriptor,
            throughDates,
            context,
            output,
            ledger ->
        IncrementalCoreThreeSnapshotBuilder()
            .buildFromVerifiedLedger(
                descriptorPath = descriptor,
                throughDates = throughDates,
                context = context,
                outputRoot = output,
                ledgerRoot = ledger
            )
    }
): CoreThreePublishableSnapshotPreparationResult {
    requirePublicationOutputRootReady(
        outputRoot
    )

    val catchUp =
        parseCoreThreePublicationCatchUpSummary(
            Files.readAllBytes(
                catchUpSummaryPath
            )
        )

    val context =
        BuildContext(
            createdAtUtc = createdAtUtc,
            sourceRepositoryCommit =
                sourceRepositoryCommit,
            revision = 1,
            buildMode = "PUBLISHABLE"
        )

    if (
        !catchUp.candidateRequired
    ) {
        return CoreThreePublishableSnapshotPreparationResult(
            status = "NO_CHANGE",
            snapshotVersion = null,
            throughDates =
                catchUp.throughDates,
            sourceRepositoryCommit =
                context.sourceRepositoryCommit,
            artifactDirectory = null
        )
    }

    val summary =
        build(
            descriptorPath,
            catchUp.throughDates,
            context,
            outputRoot,
            ledgerRoot
        )

    require(
        summary.status ==
            "BUILT"
    ) {
        "PUBLISHABLE_BUILD_NOT_BUILT"
    }

    require(
        summary.publishable
    ) {
        "PUBLISHABLE_BUILD_FLAG_FALSE"
    }

    require(
        summary.sourceRepositoryCommit ==
            context.sourceRepositoryCommit
    ) {
        "PUBLISHABLE_BUILD_SOURCE_COMMIT_MISMATCH"
    }

    val expectedNewDrawCount =
        catchUp.newlyWrittenLedgerCount +
            catchUp.reusedLedgerCount

    require(
        summary.newDrawCount ==
            expectedNewDrawCount
    ) {
        "PUBLISHABLE_BUILD_DRAW_COUNT_MISMATCH"
    }

    require(
        summary.memberCount ==
            13
    ) {
        "PUBLISHABLE_BUILD_MEMBER_COUNT_MISMATCH"
    }

    val artifactDirectory =
        outputRoot.resolve(
            "artifact"
        )

    val metadataPath =
        artifactDirectory.resolve(
            "BUILD_METADATA.json"
        )

    require(
        Files.isRegularFile(
            metadataPath
        )
    ) {
        "PUBLISHABLE_BUILD_METADATA_MISSING"
    }

    val metadata =
        parseCoreThreePublicationBuildMetadata(
            Files.readAllBytes(
                metadataPath
            )
        )

    require(
        metadata.sourceRepositoryCommit ==
            context.sourceRepositoryCommit
    ) {
        "BUILD_METADATA_SOURCE_COMMIT_MISMATCH"
    }

    require(
        metadata.cutoffs ==
            catchUp.throughDates
    ) {
        "BUILD_METADATA_FRONTIER_MISMATCH"
    }

    require(
        metadata.snapshotVersion ==
            summary.snapshotVersion
    ) {
        "BUILD_METADATA_SNAPSHOT_VERSION_MISMATCH"
    }

    require(
        metadata.assetSha256 ==
            summary.archiveSha256
    ) {
        "BUILD_METADATA_ASSET_SHA_MISMATCH"
    }

    require(
        metadata.assetByteSize ==
            summary.archiveByteSize
    ) {
        "BUILD_METADATA_ASSET_SIZE_MISMATCH"
    }

    require(
        metadata.bundleContentId ==
            summary.bundleContentId
    ) {
        "BUILD_METADATA_BUNDLE_ID_MISMATCH"
    }

    require(
        metadata.snapshotManifestSha256 ==
            summary.manifestSha256
    ) {
        "BUILD_METADATA_MANIFEST_SHA_MISMATCH"
    }

    require(
        metadata.recordCounts ==
            summary.counts
    ) {
        "BUILD_METADATA_RECORD_COUNT_MISMATCH"
    }

    return CoreThreePublishableSnapshotPreparationResult(
        status = "BUILT",
        snapshotVersion =
            summary.snapshotVersion,
        throughDates =
            catchUp.throughDates,
        sourceRepositoryCommit =
            context.sourceRepositoryCommit,
        artifactDirectory =
            artifactDirectory
    )
}

private fun requirePublicationOutputRootReady(
    outputRoot: Path
) {
    if (
        !Files.exists(
            outputRoot
        )
    ) {
        return
    }

    require(
        Files.isDirectory(
            outputRoot
        )
    ) {
        "PUBLICATION_OUTPUT_ROOT_NOT_DIRECTORY"
    }

    Files.list(
        outputRoot
    ).use { stream ->
        require(
            !stream
                .findAny()
                .isPresent
        ) {
            "PUBLICATION_OUTPUT_ROOT_NOT_EMPTY"
        }
    }
}

object CoreThreePublishableSnapshotPreparationCli {
    @JvmStatic
    fun main(
        args: Array<String>
    ) {
        require(
            args.size ==
                6
        ) {
            "expected descriptorPath catchUpSummaryPath ledgerRoot " +
                "sourceRepositoryCommit createdAtUtc outputRoot"
        }

        val result =
            prepareCoreThreePublishableSnapshot(
                descriptorPath =
                    Path.of(
                        args[0]
                    ),
                catchUpSummaryPath =
                    Path.of(
                        args[1]
                    ),
                ledgerRoot =
                    Path.of(
                        args[2]
                    ),
                sourceRepositoryCommit =
                    args[3],
                createdAtUtc =
                    Instant.parse(
                        args[4]
                    ),
                outputRoot =
                    Path.of(
                        args[5]
                    )
            )

        val output =
            linkedMapOf<String, Any?>(
                "status" to
                    result.status,
                "snapshotVersion" to
                    result.snapshotVersion,
                "throughDates" to
                    CORE_THREE_PUBLICATION_GAMES
                        .associateWith { game ->
                            result.throughDates
                                .getValue(
                                    game
                                )
                                .toString()
                        },
                "sourceRepositoryCommit" to
                    result.sourceRepositoryCommit,
                "artifactDirectory" to
                    result.artifactDirectory
                        ?.toString()
            )

        print(
            B2_JSON.writeValueAsString(
                output
            ) +
                "\n"
        )
    }
}

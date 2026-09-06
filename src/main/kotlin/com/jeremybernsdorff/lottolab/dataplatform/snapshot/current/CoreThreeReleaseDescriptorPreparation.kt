package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

private const val CORE_THREE_RELEASE_REPOSITORY =
    "jpachoulli/lotto-lab-data-platform"

internal typealias CoreThreePublicationCandidateValidation =
    (Path) -> CandidateValidationSummary

internal data class CoreThreeReleaseDescriptorPreparationResult(
    val snapshotVersion: String,
    val releaseTag: String,
    val assetName: String,
    val sidecarName: String,
    val descriptorBytes: ByteArray,
    val descriptorSha256: String
)

internal fun prepareCoreThreeReleaseDescriptor(
    artifactDirectory: Path,
    publishedAtUtc: Instant,
    validateCandidate: CoreThreePublicationCandidateValidation = {
        CoreThreeSnapshotCandidateValidator.validate(it)
    }
): CoreThreeReleaseDescriptorPreparationResult {
    val metadataPath =
        artifactDirectory.resolve("BUILD_METADATA.json")

    require(Files.isRegularFile(metadataPath)) {
        "PUBLICATION_BUILD_METADATA_MISSING"
    }

    val metadata =
        parseCoreThreePublicationBuildMetadata(
            Files.readAllBytes(metadataPath)
        )

    val snapshotVersion =
        metadata.snapshotVersion

    val releaseTag =
        snapshotVersion

    val assetName =
        "$snapshotVersion.zip"

    val sidecarName =
        "$assetName.sha256"

    val assetPath =
        artifactDirectory.resolve(assetName)

    val sidecarPath =
        artifactDirectory.resolve(sidecarName)

    val manifestPath =
        artifactDirectory.resolve("manifest.json")

    require(Files.isRegularFile(assetPath)) {
        "PUBLICATION_ASSET_MISSING"
    }

    require(Files.isRegularFile(sidecarPath)) {
        "PUBLICATION_SIDECAR_MISSING"
    }

    require(Files.isRegularFile(manifestPath)) {
        "PUBLICATION_MANIFEST_MISSING"
    }

    val assetBytes =
        Files.readAllBytes(assetPath)

    require(
        assetBytes.size.toLong() ==
            metadata.assetByteSize
    ) {
        "PUBLICATION_ASSET_SIZE_MISMATCH"
    }

    require(
        sha256(assetBytes) ==
            metadata.assetSha256
    ) {
        "PUBLICATION_ASSET_SHA256_MISMATCH"
    }

    val expectedSidecar =
        "${metadata.assetSha256}  $assetName\n"

    require(
        Files.readAllBytes(sidecarPath)
            .contentEquals(
                expectedSidecar.toByteArray(Charsets.UTF_8)
            )
    ) {
        "PUBLICATION_SIDECAR_MISMATCH"
    }

    val manifestBytes =
        Files.readAllBytes(manifestPath)

    require(
        sha256(manifestBytes) ==
            metadata.snapshotManifestSha256
    ) {
        "PUBLICATION_MANIFEST_SHA256_MISMATCH"
    }

    val manifest =
        B2_JSON.readTree(manifestBytes)

    require(
        manifest?.isObject ==
            true
    ) {
        "PUBLICATION_MANIFEST_NOT_OBJECT"
    }

    require(
        manifest["snapshotVersion"]?.textValue() ==
            metadata.snapshotVersion
    ) {
        "PUBLICATION_MANIFEST_SNAPSHOT_VERSION_MISMATCH"
    }

    require(
        manifest["sourceRepositoryCommit"]?.textValue() ==
            metadata.sourceRepositoryCommit
    ) {
        "PUBLICATION_MANIFEST_SOURCE_COMMIT_MISMATCH"
    }

    require(
        manifest["bundleContentId"]?.textValue() ==
            metadata.bundleContentId
    ) {
        "PUBLICATION_MANIFEST_BUNDLE_ID_MISMATCH"
    }

    val validation =
        validateCandidate(assetPath)

    require(
        validation.memberCount ==
            13
    ) {
        "PUBLICATION_CANDIDATE_MEMBER_COUNT_MISMATCH"
    }

    require(
        validation.gameCounts ==
            metadata.recordCounts
    ) {
        "PUBLICATION_CANDIDATE_RECORD_COUNT_MISMATCH"
    }

    require(
        validation.bundleContentId ==
            metadata.bundleContentId
    ) {
        "PUBLICATION_CANDIDATE_BUNDLE_ID_MISMATCH"
    }

    val releaseBase =
        "https://github.com/$CORE_THREE_RELEASE_REPOSITORY/releases"

    val releaseUrl =
        "$releaseBase/tag/$releaseTag"

    val downloadBase =
        "$releaseBase/download/$releaseTag"

    val descriptor =
        linkedMapOf<String, Any>(
            "schemaId" to
                "lotto-lab-core-three-release-descriptor-v1",
            "schemaVersion" to
                1,
            "snapshotVersion" to
                snapshotVersion,
            "releaseTag" to
                releaseTag,
            "releaseUrl" to
                releaseUrl,
            "assetName" to
                assetName,
            "assetUrl" to
                "$downloadBase/$assetName",
            "assetSha256" to
                metadata.assetSha256,
            "assetByteSize" to
                metadata.assetByteSize,
            "sidecarName" to
                sidecarName,
            "sidecarUrl" to
                "$downloadBase/$sidecarName",
            "bundleContentId" to
                metadata.bundleContentId,
            "snapshotManifestSha256" to
                metadata.snapshotManifestSha256,
            "sourceRepositoryCommit" to
                metadata.sourceRepositoryCommit,
            "publishedAtUtc" to
                publishedAtUtc.toString()
        )

    val descriptorBytes =
        (
            B2_JSON.writeValueAsString(descriptor) +
                "\n"
            ).toByteArray(Charsets.UTF_8)

    return CoreThreeReleaseDescriptorPreparationResult(
        snapshotVersion =
            snapshotVersion,
        releaseTag =
            releaseTag,
        assetName =
            assetName,
        sidecarName =
            sidecarName,
        descriptorBytes =
            descriptorBytes,
        descriptorSha256 =
            sha256(descriptorBytes)
    )
}

internal fun writePreparedCoreThreeReleaseDescriptor(
    outputPath: Path,
    bytes: ByteArray
): String {
    outputPath.toAbsolutePath()
        .parent
        ?.let(Files::createDirectories)

    if (Files.exists(outputPath)) {
        require(
            Files.isRegularFile(outputPath) &&
                Files.readAllBytes(outputPath)
                    .contentEquals(bytes)
        ) {
            "PUBLICATION_DESCRIPTOR_OUTPUT_CONFLICT"
        }

        return "ALREADY_PRESENT"
    }

    Files.write(
        outputPath,
        bytes,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
    )

    require(
        Files.readAllBytes(outputPath)
            .contentEquals(bytes)
    ) {
        "PUBLICATION_DESCRIPTOR_OUTPUT_READBACK_MISMATCH"
    }

    return "WRITTEN"
}

object CoreThreeReleaseDescriptorPreparationCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "expected artifactDirectory publishedAtUtc outputDescriptor"
        }

        val result =
            prepareCoreThreeReleaseDescriptor(
                artifactDirectory =
                    Path.of(args[0]),
                publishedAtUtc =
                    Instant.parse(args[1])
            )

        val outputPath =
            Path.of(args[2])

        val writeResult =
            writePreparedCoreThreeReleaseDescriptor(
                outputPath,
                result.descriptorBytes
            )

        val summary =
            linkedMapOf<String, Any>(
                "status" to
                    "PREPARED",
                "snapshotVersion" to
                    result.snapshotVersion,
                "releaseTag" to
                    result.releaseTag,
                "assetName" to
                    result.assetName,
                "sidecarName" to
                    result.sidecarName,
                "descriptorPath" to
                    outputPath.toString(),
                "descriptorSha256" to
                    result.descriptorSha256,
                "writeResult" to
                    writeResult,
                "remoteMutation" to
                    false
            )

        print(
            B2_JSON.writeValueAsString(summary) +
                "\n"
        )
    }
}

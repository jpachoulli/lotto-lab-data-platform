package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreThreeReleaseDescriptorPreparationTest {
    private val snapshotVersion =
        "core-three-pb-2026-09-02-mm-2026-09-01-la-2026-09-02-v1"

    private val sourceCommit =
        "1".repeat(40)

    private val recordCounts =
        linkedMapOf(
            "powerball" to 3850,
            "mega_millions" to 3056,
            "lotto_america" to 1135
        )

    private val cutoffs =
        linkedMapOf(
            "powerball" to "2026-09-02",
            "mega_millions" to "2026-09-01",
            "lotto_america" to "2026-09-02"
        )

    private val bundleId =
        "b".repeat(64)

    private val publishedAt =
        Instant.parse("2026-09-06T20:00:00Z")

    @Test
    fun publishableArtifactProducesExactVersionScopedDescriptor() {
        val fixture =
            fixture()

        val result =
            prepareCoreThreeReleaseDescriptor(
                fixture.artifactDirectory,
                publishedAt,
                fixture.validator
            )

        val node =
            B2_JSON.readTree(result.descriptorBytes)

        assertEquals(
            setOf(
                "schemaId",
                "schemaVersion",
                "snapshotVersion",
                "releaseTag",
                "releaseUrl",
                "assetName",
                "assetUrl",
                "assetSha256",
                "assetByteSize",
                "sidecarName",
                "sidecarUrl",
                "bundleContentId",
                "snapshotManifestSha256",
                "sourceRepositoryCommit",
                "publishedAtUtc"
            ),
            node.fieldNames().asSequence().toSet()
        )

        assertEquals(
            "lotto-lab-core-three-release-descriptor-v1",
            node["schemaId"].textValue()
        )

        assertEquals(
            1,
            node["schemaVersion"].intValue()
        )

        assertEquals(
            snapshotVersion,
            node["snapshotVersion"].textValue()
        )

        assertEquals(
            snapshotVersion,
            node["releaseTag"].textValue()
        )

        val assetName =
            "$snapshotVersion.zip"

        val sidecarName =
            "$assetName.sha256"

        assertEquals(
            assetName,
            node["assetName"].textValue()
        )

        assertEquals(
            sidecarName,
            node["sidecarName"].textValue()
        )

        assertEquals(
            "https://github.com/jpachoulli/lotto-lab-data-platform/releases/tag/$snapshotVersion",
            node["releaseUrl"].textValue()
        )

        assertEquals(
            "https://github.com/jpachoulli/lotto-lab-data-platform/releases/download/$snapshotVersion/$assetName",
            node["assetUrl"].textValue()
        )

        assertEquals(
            "https://github.com/jpachoulli/lotto-lab-data-platform/releases/download/$snapshotVersion/$sidecarName",
            node["sidecarUrl"].textValue()
        )

        assertEquals(
            fixture.assetSha,
            node["assetSha256"].textValue()
        )

        assertEquals(
            fixture.assetBytes.size.toLong(),
            node["assetByteSize"].longValue()
        )

        assertEquals(
            bundleId,
            node["bundleContentId"].textValue()
        )

        assertEquals(
            fixture.manifestSha,
            node["snapshotManifestSha256"].textValue()
        )

        assertEquals(
            sourceCommit,
            node["sourceRepositoryCommit"].textValue()
        )

        assertEquals(
            publishedAt.toString(),
            node["publishedAtUtc"].textValue()
        )

        PublishedCoreThreeSnapshotReader()
            .validateDescriptor(node)

        assertTrue(
            result.descriptorBytes.last() ==
                '\n'.code.toByte()
        )
    }

    @Test
    fun sameInputsProduceByteIdenticalDescriptor() {
        val fixture =
            fixture()

        val first =
            prepareCoreThreeReleaseDescriptor(
                fixture.artifactDirectory,
                publishedAt,
                fixture.validator
            )

        val second =
            prepareCoreThreeReleaseDescriptor(
                fixture.artifactDirectory,
                publishedAt,
                fixture.validator
            )

        assertContentEquals(
            first.descriptorBytes,
            second.descriptorBytes
        )

        assertEquals(
            first.descriptorSha256,
            second.descriptorSha256
        )
    }

    @Test
    fun nonPublishableBuildMetadataFailsClosed() {
        val fixture =
            fixture(
                buildMode = "CANDIDATE",
                publishable = false
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                prepareCoreThreeReleaseDescriptor(
                    fixture.artifactDirectory,
                    publishedAt,
                    fixture.validator
                )
            }

        assertTrue(
            failure.message.orEmpty()
                .contains(
                    "BUILD_METADATA_MODE_NOT_PUBLISHABLE"
                )
        )
    }

    @Test
    fun assetShaMismatchFailsClosed() {
        val fixture =
            fixture()

        Files.writeString(
            fixture.artifactDirectory.resolve(
                "$snapshotVersion.zip"
            ),
            "mutated"
        )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                prepareCoreThreeReleaseDescriptor(
                    fixture.artifactDirectory,
                    publishedAt,
                    fixture.validator
                )
            }

        assertTrue(
            failure.message.orEmpty()
                .contains(
                    "PUBLICATION_ASSET_SIZE_MISMATCH"
                ) ||
                failure.message.orEmpty()
                    .contains(
                        "PUBLICATION_ASSET_SHA256_MISMATCH"
                    )
        )
    }

    @Test
    fun sidecarMismatchFailsClosed() {
        val fixture =
            fixture()

        Files.writeString(
            fixture.artifactDirectory.resolve(
                "$snapshotVersion.zip.sha256"
            ),
            "wrong\n"
        )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                prepareCoreThreeReleaseDescriptor(
                    fixture.artifactDirectory,
                    publishedAt,
                    fixture.validator
                )
            }

        assertTrue(
            failure.message.orEmpty()
                .contains(
                    "PUBLICATION_SIDECAR_MISMATCH"
                )
        )
    }

    @Test
    fun manifestSourceCommitMismatchFailsClosed() {
        val fixture =
            fixture(
                manifestSourceCommit =
                    "2".repeat(40)
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                prepareCoreThreeReleaseDescriptor(
                    fixture.artifactDirectory,
                    publishedAt,
                    fixture.validator
                )
            }

        assertTrue(
            failure.message.orEmpty()
                .contains(
                    "PUBLICATION_MANIFEST_SOURCE_COMMIT_MISMATCH"
                )
        )
    }

    @Test
    fun candidateRecordCountMismatchFailsClosed() {
        val fixture =
            fixture(
                validationCounts =
                    recordCounts +
                        (
                            "powerball" to
                                3849
                            )
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                prepareCoreThreeReleaseDescriptor(
                    fixture.artifactDirectory,
                    publishedAt,
                    fixture.validator
                )
            }

        assertTrue(
            failure.message.orEmpty()
                .contains(
                    "PUBLICATION_CANDIDATE_RECORD_COUNT_MISMATCH"
                )
        )
    }

    @Test
    fun candidateBundleMismatchFailsClosed() {
        val fixture =
            fixture(
                validationBundleId =
                    "c".repeat(64)
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                prepareCoreThreeReleaseDescriptor(
                    fixture.artifactDirectory,
                    publishedAt,
                    fixture.validator
                )
            }

        assertTrue(
            failure.message.orEmpty()
                .contains(
                    "PUBLICATION_CANDIDATE_BUNDLE_ID_MISMATCH"
                )
        )
    }

    @Test
    fun descriptorOutputIsImmutableAndConflictsFailClosed() {
        val fixture =
            fixture()

        val result =
            prepareCoreThreeReleaseDescriptor(
                fixture.artifactDirectory,
                publishedAt,
                fixture.validator
            )

        val output =
            Files.createTempDirectory(
                "descriptor-output"
            ).resolve(
                "descriptor.json"
            )

        assertEquals(
            "WRITTEN",
            writePreparedCoreThreeReleaseDescriptor(
                output,
                result.descriptorBytes
            )
        )

        assertEquals(
            "ALREADY_PRESENT",
            writePreparedCoreThreeReleaseDescriptor(
                output,
                result.descriptorBytes
            )
        )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                writePreparedCoreThreeReleaseDescriptor(
                    output,
                    "{}\n".toByteArray()
                )
            }

        assertTrue(
            failure.message.orEmpty()
                .contains(
                    "PUBLICATION_DESCRIPTOR_OUTPUT_CONFLICT"
                )
        )
    }

    private fun fixture(
        buildMode: String = "PUBLISHABLE",
        publishable: Boolean = true,
        manifestSourceCommit: String =
            sourceCommit,
        validationCounts: Map<String, Int> =
            recordCounts,
        validationBundleId: String =
            bundleId
    ): Fixture {
        val artifactDirectory =
            Files.createTempDirectory(
                "release-descriptor-fixture"
            )

        val assetName =
            "$snapshotVersion.zip"

        val assetBytes =
            "synthetic-test-archive".toByteArray()

        val assetSha =
            sha256(assetBytes)

        Files.write(
            artifactDirectory.resolve(
                assetName
            ),
            assetBytes
        )

        Files.writeString(
            artifactDirectory.resolve(
                "$assetName.sha256"
            ),
            "$assetSha  $assetName\n"
        )

        val manifestBytes =
            (
                B2_JSON.writeValueAsString(
                    linkedMapOf(
                        "snapshotVersion" to
                            snapshotVersion,
                        "sourceRepositoryCommit" to
                            manifestSourceCommit,
                        "bundleContentId" to
                            bundleId
                    )
                ) +
                    "\n"
                ).toByteArray()

        val manifestSha =
            sha256(manifestBytes)

        Files.write(
            artifactDirectory.resolve(
                "manifest.json"
            ),
            manifestBytes
        )

        val metadata =
            linkedMapOf<String, Any>(
                "snapshotVersion" to
                    snapshotVersion,
                "assetSha256" to
                    assetSha,
                "assetByteSize" to
                    assetBytes.size.toLong(),
                "bundleContentId" to
                    bundleId,
                "snapshotManifestSha256" to
                    manifestSha,
                "recordCounts" to
                    recordCounts,
                "cutoffs" to
                    cutoffs,
                "buildMode" to
                    buildMode,
                "sourceRepositoryCommit" to
                    sourceCommit,
                "publishable" to
                    publishable
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

        val validator:
            CoreThreePublicationCandidateValidation = {
                CandidateValidationSummary(
                    recordTotal =
                        validationCounts.values.sum(),
                    gameCounts =
                        validationCounts,
                    coverageRows =
                        15,
                    memberCount =
                        13,
                    bundleContentId =
                        validationBundleId
                )
            }

        return Fixture(
            artifactDirectory =
                artifactDirectory,
            assetBytes =
                assetBytes,
            assetSha =
                assetSha,
            manifestSha =
                manifestSha,
            validator =
                validator
        )
    }

    private data class Fixture(
        val artifactDirectory: Path,
        val assetBytes: ByteArray,
        val assetSha: String,
        val manifestSha: String,
        val validator:
            CoreThreePublicationCandidateValidation
    )
}

package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreThreePublicationWorkflowGuardTest {
    private val publicationWorkflow =
        Path.of(
            ".github",
            "workflows",
            "core-three-publication.yml"
        )

    private val ciWorkflow =
        Path.of(
            ".github",
            "workflows",
            "ci.yml"
        )

    @Test
    fun scheduledPublicationWorkflowHasExactWriteCapableSafetyShape() {
        val workflow =
            canonicalText(
                publicationWorkflow
            )

        assertTrue(
            workflow.contains(
                "workflow_dispatch:"
            )
        )

        assertTrue(
            workflow.contains(
                "schedule:\n    - cron: \"17 12,18 * * *\""
            )
        )

        assertEquals(
            1,
            Regex(
                """(?m)^\s*-\s+cron:"""
            ).findAll(
                workflow
            ).count()
        )

        assertTrue(
            workflow.contains(
                "permissions:\n  contents: write"
            )
        )

        assertTrue(
            workflow.contains(
                "group: core-three-publication"
            )
        )

        assertTrue(
            workflow.contains(
                "cancel-in-progress: false"
            )
        )

        assertTrue(
            workflow.contains(
                "fetch-depth: 0"
            )
        )

        assertTrue(
            workflow.contains(
                "GH_TOKEN:"
            ) &&
                workflow.contains(
                    "github.token"
                )
        )

        assertFalse(
            workflow.contains(
                "secrets."
            )
        )

        assertFalse(
            workflow.contains(
                "--force"
            )
        )

        assertFalse(
            workflow.contains(
                "gh release upload"
            )
        )

        val requiredOrder =
            listOf(
                "./gradlew test",
                "Catch up verified ledger",
                "git commit -m \"DATA: advance verified core-three ledger\"",
                "Build PUBLISHABLE snapshot",
                "buildCoreThreePublishableSnapshot",
                "Preflight artifact through accepted descriptor boundary",
                "Create or verify immutable GitHub Release",
                "gh release create",
                "gh release download",
                "Advance immutable descriptor and latest last",
                "VERSIONED_DESCRIPTOR=\"\${RELEASE_DESCRIPTOR_DIR}/\${RELEASE_TAG}.json\"",
                "-PoutputDescriptor=\"\${VERSIONED_DESCRIPTOR}\"",
                "cp \"\${VERSIONED_DESCRIPTOR}\" \"\${LATEST_PATH}\"",
                "git commit -m \"DATA: publish verified core-three snapshot"
            )

        var previous =
            -1

        requiredOrder.forEach { token ->
            val index =
                workflow.indexOf(
                    token
                )

            assertTrue(
                index >= 0,
                "missing workflow token: $token"
            )

            assertTrue(
                index >
                    previous,
                "workflow ordering violation at: $token"
            )

            previous =
                index
        }

        assertEquals(
            1,
            Regex(
                """\bgh release create\b"""
            ).findAll(
                workflow
            ).count()
        )

        assertEquals(
            1,
            Regex(
                """\bgh release download\b"""
            ).findAll(
                workflow
            ).count()
        )
    }

    @Test
    fun existingData1CiRemainsReadOnly() {
        val ci =
            canonicalText(
                ciWorkflow
            )

        assertTrue(
            ci.contains(
                "permissions:\n  contents: read"
            )
        )

        assertFalse(
            ci.contains(
                "contents: write"
            )
        )

        assertFalse(
            ci.contains(
                "core-three-publication"
            )
        )
    }

    private fun canonicalText(
        path: Path
    ): String {
        val raw =
            Files.readString(
                path
            )

        val canonical =
            raw.replace(
                "\r\n",
                "\n"
            )

        require(
            '\r' !in
                canonical
        ) {
            "WORKFLOW_BARE_CR"
        }

        return canonical
    }
}

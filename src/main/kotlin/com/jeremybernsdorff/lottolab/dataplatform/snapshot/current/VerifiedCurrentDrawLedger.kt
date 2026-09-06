package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.JsonNode
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.OfficialDrawObservation
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate

object VerifiedCurrentDrawLedger {

    private const val SCHEMA_ID = "lotto-lab-verified-current-draw-ledger-v1"

    private val ROOT_KEYS = setOf(
        "schemaId", "schemaVersion", "stableId", "gameId", "eraId",
        "drawDate", "mainValues", "bonusValues", "observations"
    )

    private val OBSERVATION_KEYS = setOf(
        "gameId", "drawDate", "mainValues", "bonusValues", "sourceId",
        "sourceOrganization", "sourceUrl", "retrievedAtUtc", "rawSha256",
        "drawMetadata"
    )

    fun bytes(draw: VerifiedCurrentDraw): ByteArray {
        CoreThreeVerifiedDrawValidator.validate(
            draw = draw,
            expectedGameId = draw.gameId,
            expectedDrawDate = draw.drawDate
        )

        val root = linkedMapOf<String, Any>(
            "schemaId" to SCHEMA_ID,
            "schemaVersion" to 1,
            "stableId" to draw.stableId,
            "gameId" to draw.gameId,
            "eraId" to draw.eraId,
            "drawDate" to draw.drawDate.toString(),
            "mainValues" to draw.mainValues,
            "bonusValues" to draw.bonusValues,
            "observations" to draw.observations.sortedBy { it.sourceId }.map { observation ->
                linkedMapOf(
                    "gameId" to observation.gameId,
                    "drawDate" to observation.drawDate.toString(),
                    "mainValues" to observation.mainValues,
                    "bonusValues" to observation.bonusValues,
                    "sourceId" to observation.sourceId,
                    "sourceOrganization" to observation.sourceOrganization,
                    "sourceUrl" to observation.sourceUrl,
                    "retrievedAtUtc" to observation.retrievedAtUtc.toString(),
                    "rawSha256" to observation.rawSha256,
                    "drawMetadata" to observation.drawMetadata.toSortedMap()
                )
            }
        )

        return B2_JSON.writeValueAsString(root).plus("\n").toByteArray(Charsets.UTF_8)
    }

    fun read(bytes: ByteArray): VerifiedCurrentDraw {
        val root = B2_JSON.readTree(bytes) ?: throw IllegalArgumentException(
            "LEDGER_JSON_ROOT_MISSING"
        )
        requireExactKeys(root, ROOT_KEYS, "ledger")
        require(requireText(root, "schemaId", "ledger") == SCHEMA_ID) {
            "LEDGER_SCHEMA_ID_MISMATCH"
        }
        require(requireInt(root, "schemaVersion", "ledger") == 1) {
            "LEDGER_SCHEMA_VERSION_MISMATCH"
        }

        val stableId = requireText(root, "stableId", "ledger")
        val gameId = requireText(root, "gameId", "ledger")
        val eraId = requireText(root, "eraId", "ledger")
        val drawDate = requireLocalDate(requireText(root, "drawDate", "ledger"), "ledger.drawDate")
        val mainValues = requireIntArray(requireNode(root, "mainValues", "ledger"), "ledger.mainValues")
        val bonusValues = requireIntArray(requireNode(root, "bonusValues", "ledger"), "ledger.bonusValues")
        val observationsNode = requireNode(root, "observations", "ledger")

        require(observationsNode.isArray) { "ledger.observations must be a JSON array" }

        val observations = observationsNode.mapIndexed { index, observation ->
            val context = "ledger.observations[$index]"
            requireExactKeys(observation, OBSERVATION_KEYS, context)
            val metadataNode = requireNode(observation, "drawMetadata", context)
            require(metadataNode.isObject) { "$context.drawMetadata must be a JSON object" }
            val metadata = metadataNode.fields().asSequence().associate { entry ->
                require(entry.value.isTextual) {
                    "$context.drawMetadata.${entry.key} must be a JSON string"
                }
                entry.key to entry.value.textValue()
            }

            OfficialDrawObservation(
                requireText(observation, "gameId", context),
                requireLocalDate(
                    requireText(observation, "drawDate", context),
                    "$context.drawDate"
                ),
                requireIntArray(
                    requireNode(observation, "mainValues", context),
                    "$context.mainValues"
                ),
                requireIntArray(
                    requireNode(observation, "bonusValues", context),
                    "$context.bonusValues"
                ),
                requireText(observation, "sourceId", context),
                requireText(observation, "sourceOrganization", context),
                requireText(observation, "sourceUrl", context),
                requireInstant(
                    requireText(observation, "retrievedAtUtc", context),
                    "$context.retrievedAtUtc"
                ),
                requireText(observation, "rawSha256", context),
                metadata
            )
        }

        val draw = VerifiedCurrentDraw(
            stableId, gameId, eraId, drawDate, mainValues, bonusValues, observations
        )

        CoreThreeVerifiedDrawValidator.validate(
            draw = draw,
            expectedGameId = gameId,
            expectedDrawDate = drawDate
        )

        val canonical = bytes(draw)
        require(bytes.contentEquals(canonical)) { "LEDGER_NONCANONICAL_BYTES" }
        return draw
    }

    fun writeImmutable(
        path: Path,
        draw: VerifiedCurrentDraw
    ): String {
        val serialized = bytes(draw)
        val targetParent = requireNotNull(path.parent) { "LEDGER_TARGET_PARENT_MISSING" }
        Files.createDirectories(targetParent)
        val stagingParent = stagingParent(path)
        val staged = Files.createTempFile(stagingParent, ".lotto-lab-ledger-", ".tmp")
        try {
            Files.write(
                staged,
                serialized,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            try {
                Files.createLink(path, staged)
                return "WRITTEN"
            } catch (alreadyExists: FileAlreadyExistsException) {
                val existing = Files.readAllBytes(path)
                if (existing.contentEquals(serialized)) {
                    return "ALREADY_PRESENT"
                }
                throw IllegalStateException(
                    "CONFLICT: ${draw.gameId} ${draw.drawDate}",
                    alreadyExists
                )
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun stagingParent(path: Path): Path {
        val gameDirectory = requireNotNull(path.parent) { "LEDGER_GAME_DIRECTORY_MISSING" }
        val ledgerRoot = requireNotNull(gameDirectory.parent) { "LEDGER_ROOT_MISSING" }
        return requireNotNull(ledgerRoot.parent) { "LEDGER_STAGING_PARENT_MISSING" }
    }

    private fun requireExactKeys(node: JsonNode, expected: Set<String>, context: String) {
        require(node.isObject) { "$context must be a JSON object" }
        val actual = node.fieldNames().asSequence().toSet()
        require(actual == expected) {
            "$context key mismatch: expected=${expected.sorted()} actual=${actual.sorted()}"
        }
    }

    private fun requireNode(node: JsonNode, field: String, context: String): JsonNode =
        node.get(field) ?: throw IllegalArgumentException("$context missing field: $field")

    private fun requireText(node: JsonNode, field: String, context: String): String {
        val value = requireNode(node, field, context)
        require(value.isTextual) { "$context.$field must be a JSON string" }
        val result = value.textValue()
        require(result.isNotBlank()) { "$context.$field must not be blank" }
        return result
    }

    private fun requireInt(node: JsonNode, field: String, context: String): Int {
        val value = requireNode(node, field, context)
        require(value.isIntegralNumber && value.canConvertToInt()) {
            "$context.$field must be an integral JSON number"
        }
        return value.intValue()
    }

    private fun requireIntArray(node: JsonNode, context: String): List<Int> {
        require(node.isArray) { "$context must be a JSON array" }
        return node.mapIndexed { index, item ->
            require(item.isIntegralNumber && item.canConvertToInt()) {
                "$context[$index] must be an integral JSON number"
            }
            item.intValue()
        }
    }

    private fun requireLocalDate(raw: String, context: String): LocalDate =
        runCatching { LocalDate.parse(raw) }.getOrElse {
            throw IllegalArgumentException("$context must be ISO LocalDate", it)
        }

    private fun requireInstant(raw: String, context: String): Instant =
        runCatching { Instant.parse(raw) }.getOrElse {
            throw IllegalArgumentException("$context must be ISO Instant", it)
        }
}

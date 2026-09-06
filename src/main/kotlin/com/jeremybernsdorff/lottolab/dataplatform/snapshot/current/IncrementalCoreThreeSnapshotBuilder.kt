package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.*
import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val SOURCE_CATALOG_KOTLIN_PATH =
    Path.of(
        "src/main/kotlin/com/jeremybernsdorff/lottolab/" +
            "dataplatform/catalog/NationalGameEraCatalog.kt"
    )

private val DETERMINISTIC_ZIP_LOCAL_TIME =
    LocalDateTime.of(
        1980,
        1,
        1,
        0,
        0,
        0
    )

internal fun canonicalSourceCatalogKotlinBytes(
    raw: ByteArray
): ByteArray {
    val text =
        raw.toString(
            Charsets.UTF_8
        )

    val normalizedLf =
        text.replace(
            "\r\n",
            "\n"
        )

    require(
        '\r' !in normalizedLf
    ) {
        "SOURCE_CATALOG_UNSUPPORTED_CR_LINE_ENDING"
    }

    return normalizedLf
        .replace(
            "\n",
            "\r\n"
        )
        .toByteArray(
            Charsets.UTF_8
        )
}

internal fun deterministicZipEntryEpochMillis(
    zoneId: ZoneId
): Long =
    DETERMINISTIC_ZIP_LOCAL_TIME
        .atZone(
            zoneId
        )
        .toInstant()
        .toEpochMilli()

data class CandidateBuildSummary(
    val status: String,
    val snapshotVersion: String,
    val newDrawCount: Int,
    val counts: Map<String, Int>,
    val memberCount: Int,
    val bundleContentId: String,
    val manifestSha256: String,
    val archiveSha256: String,
    val archiveByteSize: Long,
    val publishable: Boolean,
    val sourceRepositoryCommit: String
)

class IncrementalCoreThreeSnapshotBuilder(
    private val baseReader: PublishedCoreThreeSnapshotReader = PublishedCoreThreeSnapshotReader(),
    private val acquirer: (String, LocalDate, Instant) -> VerifiedCurrentDraw = { game, date, instant ->
        val result = DualOfficialCurrentDrawAcquirer(CurrentDrawSourceRegistry.production()).acquire(game, date, instant)
        (result as? CurrentDrawAcquisitionResult.Verified)?.draw
            ?: error("CURRENT_DRAW_NOT_VERIFIED game=$game date=$date result=$result")
    }
) {
    private val games = listOf("powerball", "mega_millions", "lotto_america")
    private val requiredMembers = listOf(
        "manifest.json", "eras.json", "draws/powerball.csv", "draws/mega_millions.csv", "draws/lotto_america.csv",
        "public_metadata/powerball.csv", "public_metadata/mega_millions.csv", "public_metadata/lotto_america.csv",
        "provenance/powerball.csv", "provenance/mega_millions.csv", "provenance/lotto_america.csv",
        "source_evidence.json", "coverage.json"
    )
    private val zeroCommit = "0000000000000000000000000000000000000000"

    fun build(
        descriptorPath: Path,
        throughDate: LocalDate,
        context: BuildContext,
        outputRoot: Path
    ): CandidateBuildSummary =
        buildInternal(
            descriptorPath,
            games.associateWith { throughDate },
            context,
            outputRoot,
            outputRoot.resolve("ledger"),
            true
        )

    fun buildFromVerifiedLedger(
        descriptorPath: Path,
        throughDates: Map<String, LocalDate>,
        context: BuildContext,
        outputRoot: Path,
        ledgerRoot: Path
    ): CandidateBuildSummary =
        buildInternal(
            descriptorPath,
            throughDates,
            context,
            outputRoot,
            ledgerRoot,
            false
        )

    private fun buildInternal(
        descriptorPath: Path,
        throughDates: Map<String, LocalDate>,
        context: BuildContext,
        outputRoot: Path,
        ledgerRoot: Path,
        acquireMissing: Boolean
    ): CandidateBuildSummary {
        require(throughDates.keys == games.toSet()) { "THROUGH_DATE_GAME_SET_MISMATCH" }
        require(context.revision == 1)
        val base = baseReader.read(descriptorPath)
        val root = outputRoot.toAbsolutePath()
        Files.createDirectories(root)
        val members = base.members.toMutableMap()
        val baseRows = games.associateWith { game -> StrictCsvCodec.parse(members.getValue("draws/$game.csv").toString(Charsets.UTF_8)) }
        val latest = baseRows.mapValues { (_, rows) -> LocalDate.parse(rows.drop(1).maxOf { it[3] }) }
        games.forEach { game -> require(throughDates.getValue(game) >= latest.getValue(game)) { "THROUGH_DATE_BEFORE_BASE_CUTOFF: $game" } }
        val inventory = inventoryLedger(ledgerRoot, latest)
        val newDraws = mutableListOf<VerifiedCurrentDraw>()
        for (game in games) {
            val dates = CoreThreeCurrentSchedule.expectedDrawDates(game, latest.getValue(game), throughDates.getValue(game))
            for (date in dates) {
                val existing = inventory[game to date]
                val draw = if (existing != null) {
                    existing
                } else if (acquireMissing) {
                    val acquired = acquirer(game, date, context.createdAtUtc)
                    CoreThreeVerifiedDrawValidator.validate(acquired, game, date)
                    val ledgerPath = ledgerRoot.resolve(game).resolve("$date.json")
                    val result = VerifiedCurrentDrawLedger.writeImmutable(ledgerPath, acquired)
                    require(result == "WRITTEN" || result == "ALREADY_PRESENT") { "UNEXPECTED_LEDGER_WRITE_RESULT: $result" }
                    VerifiedCurrentDrawLedger.read(Files.readAllBytes(ledgerPath))
                } else {
                    throw IllegalStateException("MISSING_VERIFIED_LEDGER_DRAW: $game/$date")
                }
                CoreThreeVerifiedDrawValidator.validate(draw, game, date)
                val baseRow = baseRows.getValue(game).drop(1).firstOrNull { it[3] == date.toString() }
                if (baseRow != null) requireCanonicalBaseMatch(game, date, baseRow, draw) else newDraws += draw
            }
        }
        for ((key, draw) in inventory) {
            val cutoff = latest.getValue(key.first)
            if (draw.drawDate <= cutoff) {
                val baseRow = baseRows.getValue(key.first).drop(1).singleOrNull { it[3] == draw.drawDate.toString() }
                    ?: error("LEDGER_DATE_NOT_IN_BASE ${draw.gameId} ${draw.drawDate}")
                requireCanonicalBaseMatch(key.first, draw.drawDate, baseRow, draw)
            } else {
                require(draw.drawDate in CoreThreeCurrentSchedule.expectedDrawDates(key.first, cutoff, throughDates.getValue(key.first))) { "OFF_SCHEDULE_PENDING_DRAW" }
            }
        }
        val status = if (newDraws.isEmpty()) "NO_CHANGE" else "BUILT"
        val allDraws = games.associateWith { game ->
            val rows = baseRows.getValue(game).toMutableList()
            newDraws.filter { it.gameId == game }.sortedBy { it.drawDate }.forEach { rows += drawRow(rows.first(), it) }
            rows
        }
        val snapshotVersion = "core-three-pb-${allDraws.getValue("powerball").drop(1).maxOf { it[3] }}-mm-${allDraws.getValue("mega_millions").drop(1).maxOf { it[3] }}-la-${allDraws.getValue("lotto_america").drop(1).maxOf { it[3] }}-v${context.revision}"
        if (newDraws.isEmpty()) return CandidateBuildSummary("NO_CHANGE", snapshotVersion, 0, allDraws.mapValues { it.value.size - 1 }, 0, "", "", "", 0, false, context.sourceRepositoryCommit)
        val publishable = context.buildMode == "PUBLISHABLE"
        members["eras.json"] = erasBytes()
        games.forEach { game ->
            members["draws/$game.csv"] = StrictCsvCodec.write(allDraws.getValue(game))
            val metadata = StrictCsvCodec.parse(members.getValue("public_metadata/$game.csv").toString(Charsets.UTF_8)).toMutableList()
            val provenance = StrictCsvCodec.parse(members.getValue("provenance/$game.csv").toString(Charsets.UTF_8)).toMutableList()
            newDraws.filter { it.gameId == game }.sortedBy { it.drawDate }.forEach { draw -> metadata += metadataRow(metadata.first(), draw); provenance += provenanceRow(provenance.first(), draw) }
            members["public_metadata/$game.csv"] = StrictCsvCodec.write(metadata); members["provenance/$game.csv"] = StrictCsvCodec.write(provenance)
        }
        members["source_evidence.json"] = sourceEvidenceBytes(members["source_evidence.json"]!!, newDraws)
        members["coverage.json"] = coverageBytes(members["coverage.json"]!!, allDraws)
        val manifest = manifestBytes(base.manifest, members, snapshotVersion, context, allDraws); members["manifest.json"] = manifest
        require(members.keys.toSet() == requiredMembers.toSet())
        val artifact = root.resolve("artifact"); Files.createDirectories(artifact); val zip = artifact.resolve("$snapshotVersion.zip"); writeZip(zip, members)
        val manifestHash = sha256(manifest); val archiveBytes = Files.readAllBytes(zip); val archiveSha = sha256(archiveBytes); Files.writeString(artifact.resolve("$snapshotVersion.zip.sha256"), "$archiveSha  ${zip.fileName}\n"); Files.write(artifact.resolve("manifest.json"), manifest)
        Files.writeString(artifact.resolve("MEMBER_SHA256.txt"), members.toSortedMap().entries.joinToString("\n") { "${sha256(it.value)}  ${it.key}  ${it.value.size}" } + "\n")
        val bundleId = B2_JSON.readTree(manifest)["bundleContentId"].asText(); Files.writeString(artifact.resolve("BUILD_METADATA.json"), B2_JSON.writeValueAsString(linkedMapOf<String, Any>("snapshotVersion" to snapshotVersion, "assetSha256" to archiveSha, "assetByteSize" to archiveBytes.size, "bundleContentId" to bundleId, "snapshotManifestSha256" to manifestHash, "recordCounts" to allDraws.mapValues { it.value.size - 1 }, "cutoffs" to allDraws.mapValues { it.value.drop(1).maxOf { row -> row[3] } }, "buildMode" to context.buildMode, "sourceRepositoryCommit" to context.sourceRepositoryCommit, "publishable" to publishable)) + "\n")
        CoreThreeSnapshotCandidateValidator.validate(zip)
        return CandidateBuildSummary("BUILT", snapshotVersion, newDraws.size, allDraws.mapValues { it.value.size - 1 }, 13, bundleId, manifestHash, archiveSha, archiveBytes.size.toLong(), publishable, context.sourceRepositoryCommit)
    }

    private fun inventoryLedger(root: Path, cutoffs: Map<String, LocalDate>): Map<Pair<String, LocalDate>, VerifiedCurrentDraw> {
        if (!Files.exists(root)) return emptyMap()
        val result = linkedMapOf<Pair<String, LocalDate>, VerifiedCurrentDraw>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                require(path.fileName.toString().endsWith(".json")) { "UNEXPECTED_LEDGER_FILE" }
                val relative = root.relativize(path); require(relative.nameCount == 2) { "UNEXPECTED_LEDGER_PATH" }
                val game = relative.getName(0).toString(); require(game in games) { "UNSUPPORTED_LEDGER_GAME" }
                val date = runCatching { LocalDate.parse(relative.getName(1).toString().removeSuffix(".json")) }.getOrElse { error("INVALID_LEDGER_DATE") }
                val draw = VerifiedCurrentDrawLedger.read(Files.readAllBytes(path))
                require(draw.gameId == game && draw.drawDate == date) { "LEDGER_PATH_IDENTITY_MISMATCH" }
                require(result.put(game to date, draw) == null) { "DUPLICATE_LEDGER_IDENTITY" }
            }
        }
        return result
    }

    private fun requireCanonicalBaseMatch(game: String, date: LocalDate, baseRow: List<String>, draw: VerifiedCurrentDraw) {
        val eras = NationalGameEraCatalog.resolve(game, date)
        require(eras.size == 1) { "CANONICAL_BASE_ERA_RESOLUTION_MISMATCH: $game/$date" }
        val rule = eras.single().drawResultRule
        val baseMain = CoreThreeDrawRuleValidator.canonicalMainValues(baseRow.subList(6, 11).map(String::toInt), rule)
        val drawMain = CoreThreeDrawRuleValidator.canonicalMainValues(draw.mainValues, rule)
        val baseBonus = listOf(baseRow[11].toInt())
        require(baseMain == drawMain && baseBonus == draw.bonusValues) { "CONFLICTING_EXISTING_CANONICAL_DRAW: $game/$date" }
    }
    private fun drawRow(header: List<String>, d: VerifiedCurrentDraw): List<String> {
        val era = NationalGameEraCatalog.resolve(d.gameId, d.drawDate).single()
        val main = CoreThreeDrawRuleValidator.canonicalMainValues(d.mainValues, era.drawResultRule)
        return listOf(d.stableId, d.gameId, d.eraId, d.drawDate.toString(), "", "DRAW") + main.map(Int::toString) + d.bonusValues.map(Int::toString) + listOf(primary(d).sourceUrl, "OFFICIAL_VERIFIED", "DUAL_OFFICIAL_CURRENT_ACQUISITION", evidenceId(d, primary(d)), "false")
    }
    private fun primary(d: VerifiedCurrentDraw): OfficialDrawObservation = d.observations.single { it.sourceId == when (d.gameId) { "powerball" -> "ny_gaming_commission_powerball"; "mega_millions" -> "ny_gaming_commission_mega_millions"; else -> "musl_lotto_america" } }
    private fun evidenceId(d: VerifiedCurrentDraw, o: OfficialDrawObservation) = "current-${d.gameId}-${d.drawDate}-${o.sourceId}-${o.rawSha256.take(12)}"
    private fun metadataRow(header: List<String>, d: VerifiedCurrentDraw): List<String> {
        val obs = d.observations.sortedBy { it.sourceId }.map { linkedMapOf<String, Any>("drawMetadata" to it.drawMetadata.toSortedMap(), "rawSha256" to it.rawSha256, "retrievedAtUtc" to it.retrievedAtUtc.toString(), "sourceId" to it.sourceId, "sourceUrl" to it.sourceUrl) }
        val extra = linkedMapOf<String, Any>("observations" to obs, "verification" to "DUAL_OFFICIAL")
        return listOf(d.stableId, "", "", "", "", B2_JSON.writeValueAsString(extra), "[]")
    }
    private fun provenanceRow(header: List<String>, d: VerifiedCurrentDraw): List<String> {
        if (d.gameId == "lotto_america") {
            val musl = d.observations.single { it.sourceId == "musl_lotto_america" }; val iowa = d.observations.single { it.sourceId == "iowa_lottery_lotto_america" }
            return listOf(d.gameId, d.drawDate.toString(), musl.sourceId, musl.sourceUrl, sha256(canonicalObservationEvidence(d, musl.sourceId).toByteArray()), musl.rawSha256, iowa.sourceId, iowa.sourceUrl, sha256(canonicalObservationEvidence(d, iowa.sourceId).toByteArray()), iowa.rawSha256)
        }
        val o = primary(d); return listOf(d.stableId, d.gameId, d.eraId, d.drawDate.toString(), evidenceId(d, o), o.sourceUrl, o.rawSha256, o.retrievedAtUtc.toString(), "S17B-2D-A8-B1-R4", o.sourceOrganization, "DUAL_OFFICIAL_CURRENT_PRIMARY", "Canonical draw accepted only after agreement with a second official source; secondary observation retained in snapshot evidence.")
    }
    private fun sourceEvidenceBytes(original: ByteArray, draws: List<VerifiedCurrentDraw>): ByteArray {
        val arr = B2_JSON.readTree(original).deepCopy<ArrayNode>()
        draws.flatMap { d -> d.observations.map { o ->
            linkedMapOf<String, Any?>("evidenceId" to evidenceId(d, o), "sourceId" to o.sourceId, "sourceOrganization" to o.sourceOrganization, "canonicalSourceUrl" to o.sourceUrl, "sourceDatasetId" to "current:${d.gameId}:${d.drawDate}", "retrievedAtUtc" to o.retrievedAtUtc.toString(), "rawSha256" to o.rawSha256, "parserVersion" to "S17B-2D-A8-B1-R4", "termsReference" to null)
        } }.sortedBy { it["evidenceId"].toString() }.forEach { item -> arr.add(B2_JSON.valueToTree(item)) }
        return (B2_JSON.writeValueAsString(arr) + "\n").toByteArray()
    }
    private fun coverageBytes(original: ByteArray, allDraws: Map<String, List<List<String>>>): ByteArray {
        val arr = B2_JSON.readTree(original).deepCopy<ArrayNode>()
        arr.forEach { n ->
            val game = n["gameId"].asText(); val era = n["eraId"].asText()
            val dates = allDraws.getValue(game).drop(1).filter { it[2] == era }.map { LocalDate.parse(it[3]) }
            val o = n as ObjectNode
            o.put("validatedDrawCount", dates.size); o.put("expectedDrawCount", dates.size)
            if (dates.isNotEmpty()) { o.put("earliestKnownDraw", dates.minOrNull().toString()); o.put("latestKnownDraw", dates.maxOrNull().toString()) }
            val catalogEra = NationalGameEraCatalog.eras.singleOrNull { it.gameId == game && it.eraId == era }
            if (catalogEra?.effectiveUntil == null) {
                o.put("missingDrawCount", 0); o.put("conflictingDrawCount", 0); o.put("coveragePercent", 100.0); o.put("sourceStatus", "BOOTSTRAP_PLUS_DUAL_OFFICIAL_CURRENT"); o.put("automationStatus", "INCREMENTAL_CURRENT_DRAW_PIPELINE")
            }
        }
        return (B2_JSON.writeValueAsString(arr) + "\n").toByteArray()
    }
    private fun erasBytes(): ByteArray {
        val eras = NationalGameEraCatalog.eras.sortedWith(compareBy({ it.gameId }, { it.effectiveFrom }))
        val root = linkedMapOf<String, Any>("schemaId" to "lotto-lab-core-three-era-catalog-v1", "eras" to eras.map { e -> linkedMapOf<String, Any?>("gameId" to e.gameId, "eraId" to e.eraId, "effectiveFrom" to e.effectiveFrom?.toString(), "effectiveUntil" to e.effectiveUntil?.toString(), "drawResultRule" to linkedMapOf("mainNumberCount" to e.drawResultRule.mainNumberCount, "mainMinimum" to e.drawResultRule.mainMinimum, "mainMaximum" to e.drawResultRule.mainMaximum, "mainNumbersUnique" to e.drawResultRule.mainNumbersUnique, "orderMatters" to e.drawResultRule.orderMatters, "bonusNumberCountMinimum" to e.drawResultRule.bonusNumberCountMinimum, "bonusNumberCountMaximum" to e.drawResultRule.bonusNumberCountMaximum, "bonusMinimum" to e.drawResultRule.bonusMinimum, "bonusMaximum" to e.drawResultRule.bonusMaximum, "bonusMayRepeat" to e.drawResultRule.bonusMayRepeat, "bonusMayOverlapMain" to e.drawResultRule.bonusMayOverlapMain)) })
        return (B2_JSON.writeValueAsString(root) + "\n").toByteArray()
    }
    private fun manifestBytes(base: JsonNode, members: Map<String, ByteArray>, version: String, c: BuildContext, all: Map<String, List<List<String>>>): ByteArray {
        val root = base.deepCopy<ObjectNode>(); root.put("snapshotVersion", version); root.put("createdAtUtc", c.createdAtUtc.toString()); root.put("totalRecordCount", all.values.sumOf { it.size - 1 }); root.put("sourceRepositoryCommit", c.sourceRepositoryCommit)
        (root["eraCatalog"] as ObjectNode).apply {
            put("sha256", sha256(members.getValue("eras.json")))
            put("byteSize", members.getValue("eras.json").size)
            put(
                "sourceCatalogKotlinSha256",
                sha256(
                    canonicalSourceCatalogKotlinBytes(
                        Files.readAllBytes(
                            SOURCE_CATALOG_KOTLIN_PATH
                        )
                    )
                )
            )
        }
        val gamesNode = root["games"] as ArrayNode; gamesNode.forEach { g -> val game=g["gameId"].asText(); val o=g as ObjectNode; o.put("recordCount", all.getValue(game).size - 1); o.put("latestDrawDate", all.getValue(game).drop(1).maxOf { it[3] }); val f=o["files"] as ObjectNode; listOf("draws" to "draws/$game.csv", "publicMetadata" to "public_metadata/$game.csv", "provenance" to "provenance/$game.csv").forEach { (k,p) -> (f[k] as ObjectNode).apply { put("sha256", sha256(members.getValue(p))); put("byteSize", members.getValue(p).size); put("recordCount", all.getValue(game).size - 1) } } }
        (root["sourceEvidence"] as ObjectNode).apply { put("sha256", sha256(members.getValue("source_evidence.json"))); put("byteSize", members.getValue("source_evidence.json").size); put("recordCount", B2_JSON.readTree(members.getValue("source_evidence.json")).size()) }
        (root["coverage"] as ObjectNode).apply { put("sha256", sha256(members.getValue("coverage.json"))); put("byteSize", members.getValue("coverage.json").size); put("recordCount", B2_JSON.readTree(members.getValue("coverage.json")).size()) }
        root.put("bundleContentId", bundleId(members))
        return (B2_JSON.writeValueAsString(root) + "\n").toByteArray()
    }
    private fun bundleId(members: Map<String, ByteArray>) = sha256(members.filterKeys { it != "manifest.json" }.toSortedMap().entries.joinToString("") { "${it.key}\u0000${sha256(it.value)}\n" }.toByteArray())
    private fun writeZip(
        path: Path,
        members: Map<String, ByteArray>
    ) {
        val zipEntryEpochMillis =
            deterministicZipEntryEpochMillis(
                ZoneId.systemDefault()
            )

        ZipOutputStream(
            Files.newOutputStream(
                path
            )
        ).use { zip ->
            zip.setLevel(
                6
            )

            members
                .toSortedMap()
                .forEach { (name, bytes) ->
                    val entry =
                        ZipEntry(
                            name
                        ).apply {
                            time =
                                zipEntryEpochMillis

                            extra =
                                ByteArray(
                                    0
                                )

                            comment =
                                null
                        }

                    zip.putNextEntry(
                        entry
                    )

                    zip.write(
                        bytes
                    )

                    zip.closeEntry()
                }
        }
    }
}

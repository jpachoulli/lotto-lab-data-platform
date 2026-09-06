package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object SnapshotSemanticMutationTestHelper {
    data class MutatedCandidate(val zipPath: Path, val manifest: ObjectNode)

    fun mutateAndResignCandidate(sourceZip: Path, outputZip: Path, mutateMembers: (MutableMap<String, ByteArray>) -> Unit): MutatedCandidate {
        val members = linkedMapOf<String, ByteArray>()
        ZipFile(sourceZip.toFile()).use { zip -> zip.entries().asSequence().forEach { e -> members[e.name] = zip.getInputStream(e).use { it.readBytes() } } }
        require(members.size == 13)
        mutateMembers(members)
        val manifest = B2_JSON.readTree(members.getValue("manifest.json")).deepCopy<ObjectNode>()
        fun updateRef(node: ObjectNode, path: String, count: Int? = null) { node.put("sha256", sha256(members.getValue(path))); node.put("byteSize", members.getValue(path).size); count?.let { node.put("recordCount", it) } }
        val eraRoot = B2_JSON.readTree(members.getValue("eras.json"))
        require(eraRoot.isObject && eraRoot["eras"]?.isArray == true)
        updateRef(manifest["eraCatalog"] as ObjectNode, "eras.json", eraRoot["eras"].size())
        updateRef(manifest["sourceEvidence"] as ObjectNode, "source_evidence.json", B2_JSON.readTree(members.getValue("source_evidence.json")).size())
        updateRef(manifest["coverage"] as ObjectNode, "coverage.json", B2_JSON.readTree(members.getValue("coverage.json")).size())
        manifest["games"].forEach { gameNode ->
            val game = gameNode["gameId"].textValue(); val gameObject = gameNode as ObjectNode
            val draws = StrictCsvCodec.parse(members.getValue("draws/$game.csv").toString(Charsets.UTF_8)).size - 1
            gameObject.put("recordCount", draws)
            updateRef(gameObject["files"]["draws"] as ObjectNode, "draws/$game.csv", draws)
            updateRef(gameObject["files"]["publicMetadata"] as ObjectNode, "public_metadata/$game.csv", StrictCsvCodec.parse(members.getValue("public_metadata/$game.csv").toString(Charsets.UTF_8)).size - 1)
            updateRef(gameObject["files"]["provenance"] as ObjectNode, "provenance/$game.csv", StrictCsvCodec.parse(members.getValue("provenance/$game.csv").toString(Charsets.UTF_8)).size - 1)
        }
        manifest.put("totalRecordCount", manifest["games"].sumOf { it["recordCount"].intValue() })
        members["manifest.json"] = (B2_JSON.writeValueAsString(manifest) + "\n").toByteArray()
        manifest.put("bundleContentId", bundleId(members))
        members["manifest.json"] = (B2_JSON.writeValueAsString(manifest) + "\n").toByteArray()
        writeZip(outputZip, members)
        assertIntegrityEnvelopeValid(outputZip)
        return MutatedCandidate(outputZip, manifest)
    }

    fun assertIntegrityEnvelopeValid(zipPath: Path) {
        ZipFile(zipPath.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList(); require(entries.size == 13); require(entries.none { it.isDirectory }); require(entries.map { it.name }.toSet() == PublishedCoreThreeSnapshotReader.REQUIRED_MEMBERS); require(entries.all { (it.extra ?: ByteArray(0)).isEmpty() })
            val bytes = entries.associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
            val manifest = B2_JSON.readTree(bytes.getValue("manifest.json"))
            val references = mutableListOf<com.fasterxml.jackson.databind.JsonNode>().apply {
                add(manifest["eraCatalog"]); add(manifest["sourceEvidence"]); add(manifest["coverage"])
                manifest["games"].forEach { game -> game["files"].elements().forEach { add(it) } }
            }
            references.forEach { reference -> val path=reference["path"].textValue(); val content=bytes.getValue(path); require(reference["sha256"].textValue() == sha256(content)); require(reference["byteSize"].longValue() == content.size.toLong()) }
            val eras = B2_JSON.readTree(bytes.getValue("eras.json")); require(manifest["eraCatalog"]["recordCount"].intValue() == eras["eras"].size())
            val evidence = B2_JSON.readTree(bytes.getValue("source_evidence.json")); require(manifest["sourceEvidence"]["recordCount"].intValue() == evidence.size())
            val coverage = B2_JSON.readTree(bytes.getValue("coverage.json")); require(manifest["coverage"]["recordCount"].intValue() == coverage.size())
            var total = 0
            manifest["games"].forEach { game -> val id=game["gameId"].textValue(); val draws=StrictCsvCodec.parse(bytes.getValue("draws/$id.csv").toString(Charsets.UTF_8)).size-1; val metadata=StrictCsvCodec.parse(bytes.getValue("public_metadata/$id.csv").toString(Charsets.UTF_8)).size-1; val provenance=StrictCsvCodec.parse(bytes.getValue("provenance/$id.csv").toString(Charsets.UTF_8)).size-1; require(game["recordCount"].intValue()==draws); require(game["files"]["draws"]["recordCount"].intValue()==draws); require(game["files"]["publicMetadata"]["recordCount"].intValue()==metadata); require(game["files"]["provenance"]["recordCount"].intValue()==provenance); total += draws }
            require(manifest["totalRecordCount"].intValue() == total)
            require(manifest["bundleContentId"].textValue() == bundleId(bytes))
        }
    }

    private fun findRef(manifest: com.fasterxml.jackson.databind.JsonNode, path: String): com.fasterxml.jackson.databind.JsonNode = when {
        path == "eras.json" -> manifest["eraCatalog"]
        path == "source_evidence.json" -> manifest["sourceEvidence"]
        path == "coverage.json" -> manifest["coverage"]
        else -> manifest["games"].first { game -> game["files"].elements().asSequence().any { it["path"].textValue() == path } }["files"].elements().asSequence().first { it["path"].textValue() == path }
    }

    private fun bundleId(members: Map<String, ByteArray>) = sha256(members.filterKeys { it != "manifest.json" }.toSortedMap().entries.joinToString("") { "${it.key}\u0000${sha256(it.value)}\n" }.toByteArray())
    private fun writeZip(path: Path, members: Map<String, ByteArray>) { ZipOutputStream(Files.newOutputStream(path)).use { zip -> zip.setLevel(6); members.toSortedMap().forEach { (name, bytes) -> ZipEntry(name).also { it.time = 315558000000L; it.extra = ByteArray(0); zip.putNextEntry(it); zip.write(bytes); zip.closeEntry() } } } }
}

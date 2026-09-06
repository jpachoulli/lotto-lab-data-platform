package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipFile

data class PublishedBaseSnapshot(val descriptor: JsonNode, val members: Map<String, ByteArray>, val manifest: JsonNode)

class PublishedCoreThreeSnapshotReader(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
) {
    companion object {
        val REQUIRED_MEMBERS = setOf(
            "manifest.json", "eras.json", "draws/powerball.csv", "draws/mega_millions.csv", "draws/lotto_america.csv",
            "public_metadata/powerball.csv", "public_metadata/mega_millions.csv", "public_metadata/lotto_america.csv",
            "provenance/powerball.csv", "provenance/mega_millions.csv", "provenance/lotto_america.csv", "source_evidence.json", "coverage.json"
        )
        private val HASH = Regex("[0-9a-f]{64}")
        private val COMMIT = Regex("[0-9a-f]{40}")
    }

    fun readDescriptor(path: Path): JsonNode = B2_JSON.readTree(Files.readAllBytes(path)).also { validateDescriptor(it) }

    fun validateDescriptor(d: JsonNode) {
        val expected = setOf("schemaId", "schemaVersion", "snapshotVersion", "releaseTag", "releaseUrl", "assetName", "assetUrl", "assetSha256", "assetByteSize", "sidecarName", "sidecarUrl", "bundleContentId", "snapshotManifestSha256", "sourceRepositoryCommit", "publishedAtUtc")
        require(d.isObject && d.fieldNames().asSequence().toSet() == expected)
        requireText(d, "schemaId") { it == "lotto-lab-core-three-release-descriptor-v1" }
        requireIntegral(d, "schemaVersion") { it == 1L }
        requireText(d, "snapshotVersion")
        requireText(d, "releaseTag")
        requireHttps(requireText(d, "releaseUrl"))
        requireSafeBasename(requireText(d, "assetName"))
        requireHttps(requireText(d, "assetUrl"))
        requireText(d, "assetSha256") { HASH.matches(it) }
        requireIntegral(d, "assetByteSize") { it in 1L..(50L * 1024L * 1024L) }
        requireSafeBasename(requireText(d, "sidecarName"))
        requireHttps(requireText(d, "sidecarUrl"))
        requireText(d, "bundleContentId") { HASH.matches(it) }
        requireText(d, "snapshotManifestSha256") { HASH.matches(it) }
        requireText(d, "sourceRepositoryCommit") { COMMIT.matches(it) }
        requireInstant(requireText(d, "publishedAtUtc"))
    }

    fun read(path: Path): PublishedBaseSnapshot {
        val descriptor = readDescriptor(path)
        val request = HttpRequest.newBuilder(URI.create(descriptor["assetUrl"].asText())).timeout(Duration.ofSeconds(60)).header("User-Agent", "LottoLabDataPlatform/0.1").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299)
        val archive = response.body()
        require(archive.size.toLong() == descriptor["assetByteSize"].asLong())
        require(sha256(archive) == descriptor["assetSha256"].asText())
        val temp = Files.createTempFile("lotto-lab-b2-base-", ".zip"); Files.write(temp, archive)
        try {
            ZipFile(temp.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val names = entries.map { it.name }
                require(names.size == REQUIRED_MEMBERS.size && names.toSet() == REQUIRED_MEMBERS)
                require(names.none { it.contains("..") || it.startsWith("/") || it.contains("\\") })
                val members = names.associateWith { name ->
                    val entry = zip.getEntry(name); require(entry != null && !entry.isDirectory)
                    require(entry.size >= 0 && entry.size <= 25L * 1024L * 1024L)
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    require(bytes.size.toLong() == entry.size); bytes
                }
                require(members.values.sumOf { it.size.toLong() } <= 100L * 1024L * 1024L)
                val manifestBytes = members.getValue("manifest.json")
                require(sha256(manifestBytes) == descriptor["snapshotManifestSha256"].asText())
                val manifest = B2_JSON.readTree(manifestBytes)
                require(manifest["snapshotVersion"].textValue() == descriptor["snapshotVersion"].textValue())
                require(manifest["sourceRepositoryCommit"].textValue() == descriptor["sourceRepositoryCommit"].textValue())
                require(manifest["bundleContentId"].textValue() == descriptor["bundleContentId"].textValue())
                require(bundleId(members) == manifest["bundleContentId"].asText())
                verifyManifestMembers(manifest, members)
                return PublishedBaseSnapshot(descriptor, members, manifest)
            }
        } finally { Files.deleteIfExists(temp) }
    }

    private fun verifyManifestMembers(manifest: JsonNode, members: Map<String, ByteArray>) {
        fun check(node: JsonNode) {
            val path = node["path"].asText()
            require(members.containsKey(path))
            require(node["sha256"].asText() == sha256(members.getValue(path)))
            if (node.has("byteSize")) require(node["byteSize"].asLong() == members.getValue(path).size.toLong())
        }
        check(manifest["eraCatalog"])
        manifest["games"].forEach { game ->
            check(game["files"]["draws"])
            check(game["files"]["publicMetadata"])
            check(game["files"]["provenance"])
        }
        check(manifest["sourceEvidence"])
        check(manifest["coverage"])
    }

    private fun bundleId(members: Map<String, ByteArray>) =
        sha256(members.filterKeys { it != "manifest.json" }.toSortedMap().entries.joinToString("") { "${it.key}\u0000${sha256(it.value)}\n" }.toByteArray())

    private fun requireText(node: JsonNode, field: String, predicate: (String) -> Boolean = { true }): String {
        val value = node.get(field)
        require(value?.isTextual == true) { "$field must be a JSON string" }
        return value.textValue().also { require(it.isNotBlank() && predicate(it)) { "invalid $field" } }
    }

    private fun requireIntegral(node: JsonNode, field: String, predicate: (Long) -> Boolean): Long {
        val value = node.get(field)
        require(value?.isIntegralNumber == true && value.canConvertToLong()) { "$field must be an integral JSON number" }
        return value.longValue().also { require(predicate(it)) { "invalid $field" } }
    }

    private fun requireInstant(value: String) { Instant.parse(value) }

    private fun requireSafeBasename(value: String) {
        require(value.isNotBlank() && value != "." && value != ".." && '/' !in value && '\\' !in value)
    }

    private fun requireHttps(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { error("invalid URI") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
    }
}

package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import java.security.MessageDigest
import java.time.Instant

val B2_JSON: ObjectMapper = ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun canonicalObservationEvidence(d: VerifiedCurrentDraw, sourceId: String): String {
    val o = d.observations.single { it.sourceId == sourceId }
    val metadataNode = B2_JSON.createObjectNode()
    o.drawMetadata.toSortedMap().forEach { (key, value) -> metadataNode.put(key, value) }
    val metadata = B2_JSON.writeValueAsString(metadataNode)
    return listOf("gameId=${o.gameId}", "drawDate=${o.drawDate}", "sourceId=${o.sourceId}", "sourceOrganization=${o.sourceOrganization}", "sourceUrl=${o.sourceUrl}", "retrievedAtUtc=${o.retrievedAtUtc}", "rawSha256=${o.rawSha256}", "mainValues=${o.mainValues.joinToString(",")}", "bonusValues=${o.bonusValues.joinToString(",")}", "metadata=$metadata").joinToString("\n") + "\n"
}
data class BuildContext(val createdAtUtc: Instant, val sourceRepositoryCommit: String, val revision: Int, val buildMode: String) {
    init { require(buildMode == "CANDIDATE" || buildMode == "PUBLISHABLE"); require(sourceRepositoryCommit.matches(Regex("[0-9a-f]{40}"))); if (buildMode == "PUBLISHABLE") require(sourceRepositoryCommit.any { it != '0' }) }
}

package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.fasterxml.jackson.databind.node.ObjectNode

class CoreThreeSnapshotCandidateValidatorTest {
    @Test fun candidateHasExactly13Members() { assertEquals(13, CoreThreeSnapshotCandidateValidator.validate(valid()).memberCount) }
    @Test fun candidateArchiveIsByteDeterministic() { val a=resourceBytes(); val b=resourceBytes(); assertEquals(a.toList(),b.toList()) }
    @Test fun candidateZipHasNoUnnecessaryExtraFields() { ZipFile(valid().toFile()).use { z -> assertEquals(setOf(0), z.entries().asSequence().map { it.extra?.size ?: 0 }.toSet()) } }
    @Test fun semanticallySwappedGameManifestPathFails() = assertShapeFailure("manifest.json", "MANIFEST_GAME_ORDER_OR_SET_MISMATCH") { val arr=it["games"] as com.fasterxml.jackson.databind.node.ArrayNode; val first=arr.remove(0); val second=arr.remove(0); arr.insert(0, second); arr.insert(1, first) }
    @Test fun unsupportedBundleContentIdAlgorithmFails() = assertShapeFailure("manifest.json", "BUNDLE_CONTENT_ID_ALGORITHM_UNSUPPORTED") { (it as ObjectNode).put("bundleContentIdAlgorithm", "unsupported") }
    @Test fun mutatedEraCatalogFailsCandidateValidation() = assertShapeFailure("eras.json", "ERA_CATALOG_KEY_SET_MISMATCH") { (it["eras"][0] as ObjectNode).put("eraId", "mutated-era") }
    @Test fun sourceEvidenceManifestCountMismatchFails() { assertFailsWith<IllegalArgumentException> { CoreThreeSnapshotCandidateValidator.validate(mutated("manifest.json") { it.replace("\"recordCount\":445", "\"recordCount\":444") }) } }
    @Test fun wholeGameCountInjectedIntoEraCoverageFails() = assertShapeFailure("coverage.json", "COVERAGE_VALIDATED_COUNT_MISMATCH") { (it[0] as ObjectNode).put("validatedDrawCount", 3850) }
    @Test fun standardProvenanceWrongGameFails() = assertCsvFailure("provenance/powerball.csv", "PROVENANCE_GAME_MISMATCH") { it[1][1] = "mega_millions" }
    @Test fun standardProvenanceWrongEraFails() = assertCsvFailure("provenance/powerball.csv", "PROVENANCE_ERA_MISMATCH") { it[1][2] = "powerball_69_26_2015" }
    @Test fun standardProvenanceWrongDateFails() = assertCsvFailure("provenance/powerball.csv", "PROVENANCE_DATE_MISMATCH") { it[1][3] = "1992-04-23" }
    @Test fun publicMetadataMalformedBooleanFails() = assertCsvFailure("public_metadata/powerball.csv", "INVALID_BOOLEAN: metadata.rollover") { it[1][3] = "TRUE" }

    @Test fun acceptedCandidatePassesExactShapeAndLogicalIdentityValidation() { val summary=CoreThreeSnapshotCandidateValidator.validate(valid()); assertEquals(8041, summary.recordTotal); assertEquals(mapOf("powerball" to 3850, "mega_millions" to 3056, "lotto_america" to 1135), summary.gameCounts) }
    @Test fun duplicateLogicalDrawIdentityWithDifferentStableIdsFails() {
        val out=Files.createTempFile("duplicate-logical", ".zip")
        val mutated=SnapshotSemanticMutationTestHelper.mutateAndResignCandidate(valid(), out) { members ->
            val drawRows=StrictCsvCodec.parse(members.getValue("draws/powerball.csv").toString(Charsets.UTF_8)).map { it.toMutableList() }.toMutableList()
            val duplicate=drawRows[1].toMutableList(); val oldId=duplicate[0]; val newId="$oldId-copy-$oldId"; duplicate[0]=newId; drawRows.add(duplicate)
            members["draws/powerball.csv"]=StrictCsvCodec.write(drawRows)
            val metadata=StrictCsvCodec.parse(members.getValue("public_metadata/powerball.csv").toString(Charsets.UTF_8)).map { it.toMutableList() }.toMutableList(); val metadataRow=metadata[1].toMutableList(); metadataRow[0]=newId; metadata.add(metadataRow); members["public_metadata/powerball.csv"]=StrictCsvCodec.write(metadata)
            val provenance=StrictCsvCodec.parse(members.getValue("provenance/powerball.csv").toString(Charsets.UTF_8)).map { it.toMutableList() }.toMutableList(); val provenanceRow=provenance[1].toMutableList(); provenanceRow[0]=newId; provenance.add(provenanceRow); members["provenance/powerball.csv"]=StrictCsvCodec.write(provenance)
        }
        assertSemanticFailure(mutated.zipPath, "DUPLICATE_LOGICAL_DRAW_IDENTITY")
    }

    @Test fun noOpResignedCandidateStillPassesProductionValidator() { val output=Files.createTempFile("noop-resigned-candidate", ".zip"); val candidate=SnapshotSemanticMutationTestHelper.mutateAndResignCandidate(valid(), output) { }; SnapshotSemanticMutationTestHelper.assertIntegrityEnvelopeValid(candidate.zipPath); assertEquals(8041, CoreThreeSnapshotCandidateValidator.validate(candidate.zipPath).recordTotal) }
    @Test fun eraExtraFieldFailsAfterIntegrityResign() = assertShapeFailure("eras.json", "era[0] key mismatch") { root -> ((root["eras"][0]) as ObjectNode).put("unexpected", true) }
    @Test fun eraMissingFieldFailsAfterIntegrityResign() = assertShapeFailure("eras.json", "era[0] key mismatch") { root -> (root["eras"][0] as ObjectNode).remove("eraId") }
    @Test fun eraNumericStringFailsAfterIntegrityResign() = assertShapeFailure("eras.json", "mainNumberCount") { root -> (root["eras"][0]["drawResultRule"] as ObjectNode).put("mainNumberCount", "5") }
    @Test fun sourceEvidenceMissingSourceDatasetIdFailsAfterIntegrityResign() = assertShapeFailure("source_evidence.json", "evidence[0] key mismatch") { root -> (root[0] as ObjectNode).remove("sourceDatasetId") }
    @Test fun sourceEvidenceMissingTermsReferenceFailsAfterIntegrityResign() = assertShapeFailure("source_evidence.json", "evidence[0] key mismatch") { root -> (root[0] as ObjectNode).remove("termsReference") }
    @Test fun sourceEvidenceExtraFieldFailsAfterIntegrityResign() = assertShapeFailure("source_evidence.json", "evidence[0] key mismatch") { root -> (root[0] as ObjectNode).put("extra", "x") }
    @Test fun sourceEvidenceWrongScalarTypeFailsAfterIntegrityResign() = assertShapeFailure("source_evidence.json", "rawSha256") { root -> (root[0] as ObjectNode).put("rawSha256", 1) }
    @Test fun coverageNumericStringFailsAfterIntegrityResign() = assertShapeFailure("coverage.json", "coverage[0].validatedDrawCount") { root -> (root[0] as ObjectNode).put("validatedDrawCount", "1") }
    @Test fun coverageMissingSourceStatusFailsAfterIntegrityResign() = assertShapeFailure("coverage.json", "coverage[0] key mismatch") { root -> (root[0] as ObjectNode).remove("sourceStatus") }
    @Test fun coverageExtraFieldFailsAfterIntegrityResign() = assertShapeFailure("coverage.json", "coverage[0] key mismatch") { root -> (root[0] as ObjectNode).put("unexpectedField", true) }
    @Test fun coverageMalformedDateFailsAfterIntegrityResign() = assertShapeFailure("coverage.json", "coverage[0].earliestKnownDraw") { root -> (root[0] as ObjectNode).put("earliestKnownDraw", "2026-99-99") }

    private fun assertSemanticFailure(zipPath:Path, expectedToken:String) { SnapshotSemanticMutationTestHelper.assertIntegrityEnvelopeValid(zipPath); val failure=assertFailsWith<IllegalArgumentException> { CoreThreeSnapshotCandidateValidator.validate(zipPath) }; assertTrue(actual = failure.message?.contains(expectedToken)==true, message = "Expected semantic failure token '$expectedToken' but got: ${failure.message}") }
    private fun assertShapeFailure(member:String, expectedToken:String, mutation:(com.fasterxml.jackson.databind.JsonNode)->Unit) {
        val out=Files.createTempFile("shape-mutation", ".zip")
        val candidate=SnapshotSemanticMutationTestHelper.mutateAndResignCandidate(valid(), out) { members -> val root=B2_JSON.readTree(members.getValue(member).toString(Charsets.UTF_8)); mutation(root); members[member]=(B2_JSON.writeValueAsString(root)+"\n").toByteArray() }
        assertSemanticFailure(candidate.zipPath, expectedToken)
    }

    private fun assertCsvFailure(member:String, expectedToken:String, mutation:(MutableList<MutableList<String>>)->Unit) {
        val out=Files.createTempFile("csv-mutation", ".zip")
        val candidate=SnapshotSemanticMutationTestHelper.mutateAndResignCandidate(valid(), out) { members -> val rows=StrictCsvCodec.parse(members.getValue(member).toString(Charsets.UTF_8)).map { it.toMutableList() }.toMutableList(); mutation(rows); members[member]=StrictCsvCodec.write(rows) }
        assertSemanticFailure(candidate.zipPath, expectedToken)
    }

    private fun valid(): Path = Files.createTempFile("candidate-valid", ".zip").also { Files.write(it, resourceBytes()) }
    private fun resourceBytes() = requireNotNull(javaClass.classLoader.getResourceAsStream("snapshot/current/accepted-candidate.zip")).use { it.readBytes() }
    private fun mutated(member: String, transform: (String) -> String): Path {
        val out=Files.createTempFile("candidate-mutated", ".zip")
        ZipFile(valid().toFile()).use { input -> ZipOutputStream(Files.newOutputStream(out)).use { output ->
            input.entries().asSequence().forEach { entry -> val e=ZipEntry(entry.name); e.time=315558000000L; e.extra=ByteArray(0); output.putNextEntry(e); var bytes=input.getInputStream(entry).use { it.readBytes() }; if(entry.name==member) bytes=transform(bytes.toString(Charsets.UTF_8)).toByteArray(); output.write(bytes); output.closeEntry() }
        } }
        return out
    }
}

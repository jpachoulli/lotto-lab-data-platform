package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerifiedCurrentDrawLedgerTest {

    private val ledgerRoot = Path.of("data/current/core_three/verified")

    private val powerballPath = ledgerRoot.resolve("powerball/2026-09-02.json")

    @Test
    fun allTwelveAcceptedLedgersStrictReadAndRoundTripByteExact() {
        val files = Files.walk(ledgerRoot).use { stream ->
            stream.filter(Files::isRegularFile).sorted().toList()
        }

        kotlin.test.assertEquals(12, files.size)

        files.forEach { path ->
            val original = Files.readAllBytes(path)
            val parsed = VerifiedCurrentDrawLedger.read(original)
            val canonical = VerifiedCurrentDrawLedger.bytes(parsed)
            assertContentEquals(original, canonical, path.toString())
        }
    }

    @Test
    fun schemaVersionStringFailsStrictParsing() {
        val root = validRoot()
        root.put("schemaVersion", "1")
        assertFailure(jsonBytes(root), "schemaVersion must be an integral JSON number")
    }

    @Test
    fun topLevelExtraFieldFailsStrictParsing() {
        val root = validRoot()
        root.put("unexpected", true)
        assertFailure(jsonBytes(root), "ledger key mismatch")
    }

    @Test
    fun observationExtraFieldFailsStrictParsing() {
        val root = validRoot()
        val observation = root["observations"][0] as ObjectNode
        observation.put("unexpected", true)
        assertFailure(jsonBytes(root), "ledger.observations[0] key mismatch")
    }

    @Test
    fun numericStringInMainValuesFailsStrictParsing() {
        val root = validRoot()
        val main = root.withArray("mainValues")
        main.set(
            0,
            B2_JSON.nodeFactory.textNode(main[0].intValue().toString())
        )
        assertFailure(
            jsonBytes(root),
            "ledger.mainValues[0] must be an integral JSON number"
        )
    }

    @Test
    fun nonStringMetadataValueFailsStrictParsing() {
        val root = validRoot()
        val observation = root["observations"][0] as ObjectNode
        val metadata = observation["drawMetadata"] as ObjectNode
        metadata.put("invalid_test_value", true)
        assertFailure(
            jsonBytes(root),
            "drawMetadata.invalid_test_value must be a JSON string"
        )
    }

    @Test
    fun wrongOfficialSourceSetFailsDuringLedgerRead() {
        val root = validRoot()
        val observation = root["observations"][1] as ObjectNode
        observation.put("sourceId", "fake_second_source")
        assertFailure(jsonBytes(root), "VERIFIED_SOURCE_SET_MISMATCH")
    }

    @Test
    fun semanticallyEquivalentNoncanonicalWhitespaceFails() {
        val original = Files.readString(powerballPath)
        val changed = (" " + original).toByteArray(Charsets.UTF_8)
        assertFailure(changed, "LEDGER_NONCANONICAL_BYTES")
    }

    @Test
    fun sameExistingImmutableBytesReturnAlreadyPresentWithoutRewrite() {
        val dir = Files.createTempDirectory("ledger-identical")
        try {
            val target = dir.resolve("powerball/2026-09-02.json")
            val draw = VerifiedCurrentDrawLedger.read(Files.readAllBytes(powerballPath))
            assertEquals("WRITTEN", VerifiedCurrentDrawLedger.writeImmutable(target, draw))
            val before = Files.readAllBytes(target)
            assertEquals("ALREADY_PRESENT", VerifiedCurrentDrawLedger.writeImmutable(target, draw))
            val after = Files.readAllBytes(target)
            assertContentEquals(before, after)
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun conflictingExistingBytesAreNeverOverwritten() {
        val dir = Files.createTempDirectory("ledger-conflict")
        try {
            val target = dir.resolve("powerball/2026-09-02.json")
            Files.createDirectories(target.parent)
            val sentinel = "DO-NOT-OVERWRITE\n".toByteArray(Charsets.UTF_8)
            Files.write(target, sentinel)
            val draw = VerifiedCurrentDrawLedger.read(Files.readAllBytes(powerballPath))
            val failure = assertFailsWith<IllegalStateException> {
                VerifiedCurrentDrawLedger.writeImmutable(target, draw)
            }
            assertTrue(failure.message?.contains("CONFLICT:") == true)
            assertContentEquals(sentinel, Files.readAllBytes(target))
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun concurrentIdenticalWritersConvergeWithoutOverwrite() {
        val dir = Files.createTempDirectory("ledger-concurrent")
        val writerCount = 8
        val rounds = 32
        val executor = Executors.newFixedThreadPool(writerCount)
        try {
            val draw = VerifiedCurrentDrawLedger.read(Files.readAllBytes(powerballPath))
            val expectedBytes = VerifiedCurrentDrawLedger.bytes(draw)
            repeat(rounds) { round ->
                val target = dir.resolve("round-$round/powerball/2026-09-02.json")
                val start = CountDownLatch(1)
                val futures = List(writerCount) {
                    executor.submit<String> {
                        start.await()
                        VerifiedCurrentDrawLedger.writeImmutable(target, draw)
                    }
                }
                start.countDown()
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it == "WRITTEN" }, "round=$round")
                assertEquals(writerCount - 1, results.count { it == "ALREADY_PRESENT" }, "round=$round")
                assertEquals(writerCount, results.size, "round=$round")
                assertContentEquals(expectedBytes, Files.readAllBytes(target), "round=$round")
                val stagedFiles = Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith(".lotto-lab-ledger-") }.count()
                }
                assertEquals(0L, stagedFiles, "staging cleanup round=$round")
            }
        } finally {
            executor.shutdownNow()
            deleteTree(dir)
        }
    }

    private fun validRoot(): ObjectNode =
        B2_JSON.readTree(Files.readAllBytes(powerballPath)) as ObjectNode

    private fun jsonBytes(root: ObjectNode): ByteArray =
        (B2_JSON.writeValueAsString(root) + "\n").toByteArray(Charsets.UTF_8)

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) {
            return
        }
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun assertFailure(bytes: ByteArray, expectedToken: String) {
        val failure = assertFailsWith<IllegalArgumentException> {
            VerifiedCurrentDrawLedger.read(bytes)
        }
        assertTrue(
            failure.message?.contains(expectedToken) == true,
            "Expected '$expectedToken', got: ${failure.message}"
        )
    }
}

package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Files

class PublishedCoreThreeSnapshotReaderTest {
    private fun descriptor(): ObjectNode = B2_JSON.readTree(Files.readString(java.nio.file.Path.of("data/distribution/core_three/latest.json"))).deepCopy()

    @Test fun futureValidDescriptorIsAccepted() {
        val d = descriptor(); d.put("snapshotVersion", "core-three-future-v1"); d.put("releaseTag", "core-three-future-v1")
        PublishedCoreThreeSnapshotReader().validateDescriptor(d)
    }

    @Test fun descriptorStringSchemaVersionFails() { val d=descriptor(); d.put("schemaVersion", "1"); assertFailsWith<IllegalArgumentException> { PublishedCoreThreeSnapshotReader().validateDescriptor(d) } }
    @Test fun descriptorStringAssetByteSizeFails() { val d=descriptor(); d.put("assetByteSize", "433104"); assertFailsWith<IllegalArgumentException> { PublishedCoreThreeSnapshotReader().validateDescriptor(d) } }
    @Test fun descriptorUnsafeAssetNameFails() { val d=descriptor(); d.put("assetName", "../x.zip"); assertFailsWith<IllegalArgumentException> { PublishedCoreThreeSnapshotReader().validateDescriptor(d) } }
    @Test fun descriptorNonHttpsUrlFails() { val d=descriptor(); d.put("assetUrl", "http://example.invalid/x.zip"); assertFailsWith<IllegalArgumentException> { PublishedCoreThreeSnapshotReader().validateDescriptor(d) } }
    @Test fun descriptorNumericAssetUrlFailsStrictStringType() { val d=descriptor(); d.put("assetUrl", 123); val e=assertFailsWith<IllegalArgumentException> { PublishedCoreThreeSnapshotReader().validateDescriptor(d) }; assertTrue(e.message.orEmpty().contains("assetUrl must be a JSON string")) }
    @Test fun descriptorNumericReleaseUrlFailsStrictStringType() { val d=descriptor(); d.put("releaseUrl", 123); val e=assertFailsWith<IllegalArgumentException> { PublishedCoreThreeSnapshotReader().validateDescriptor(d) }; assertTrue(e.message.orEmpty().contains("releaseUrl must be a JSON string")) }
    @Test fun descriptorNumericSidecarUrlFailsStrictStringType() { val d=descriptor(); d.put("sidecarUrl", 123); val e=assertFailsWith<IllegalArgumentException> { PublishedCoreThreeSnapshotReader().validateDescriptor(d) }; assertTrue(e.message.orEmpty().contains("sidecarUrl must be a JSON string")) }
    @Test fun descriptorFutureValuesAreNotAugustHardcoded() { val d=descriptor(); d.put("snapshotVersion", "future-pb-mm-la-v1"); d.put("releaseTag", "future-pb-mm-la-v1"); PublishedCoreThreeSnapshotReader().validateDescriptor(d); assertTrue(true) }
}

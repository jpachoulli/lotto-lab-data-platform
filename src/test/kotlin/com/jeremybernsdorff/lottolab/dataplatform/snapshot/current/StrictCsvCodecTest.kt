package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrictCsvCodecTest {
    @Test fun quotedCommaEscapedQuoteAndNewlineRoundTrip() {
        val rows = listOf(listOf("h1", "h2"), listOf("a,b", "say \"hi\"\nnext"))
        assertEquals(rows, StrictCsvCodec.parse(StrictCsvCodec.write(rows).toString(Charsets.UTF_8)))
    }

    @Test fun crlfInputIsAccepted() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), StrictCsvCodec.parse("a,b\r\n1,2\r\n"))
    }

    @Test fun illegalQuoteInsideUnquotedFieldFails() {
        assertFailsWith<IllegalArgumentException> { StrictCsvCodec.parse("ab\"cd,1\n") }
    }

    @Test fun contentAfterClosingQuoteFails() {
        assertFailsWith<IllegalArgumentException> { StrictCsvCodec.parse("\"a\"x,b\n") }
    }

    @Test fun unterminatedQuoteFails() {
        assertFailsWith<IllegalArgumentException> { StrictCsvCodec.parse("\"a,b\n") }
    }

    @Test fun rowWidthMismatchFails() {
        assertFailsWith<IllegalArgumentException> { StrictCsvCodec.parse("a,b\n1\n") }
    }
}

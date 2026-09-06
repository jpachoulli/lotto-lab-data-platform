package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CurrentDrawSourceParserTest {
    private val date = LocalDate.parse("2026-09-02")
    private val now = Instant.parse("2026-09-03T00:00:00Z")
    private fun http(body: String) = OfficialDrawHttpClient { url ->
        val bytes = body.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        OfficialHttpResponse(url, 200, bytes, hash)
    }
    private fun iowaHtml(numbers: String = "2 - 4 - 16 - 39 - 45 - 6", asb: String = "3") = """
        <html><body>
        <section><h1>Latest Winning Numbers</h1><div>Powerball 3 10 29 58 64 14</div></section>
        <section><h2>Mega Millions</h2><div>1 22 51 61 63 17</div></section>
        <h1>Lotto America</h1>
        <table><thead><tr><th>Date</th><th>Numbers</th><th>All Star Bonus</th></tr></thead>
        <tbody><tr><td>9/2/2026</td><td>$numbers</td><td>$asb</td></tr>
        <tr><td>9/1/2026</td><td>1 - 2 - 3 - 4 - 5 - 6</td><td>2</td></tr></tbody></table>
        </body></html>
    """.trimIndent()
    private fun delawareHtml(dateText: String = "9/2/2026", values: String = "03 10 29 58 64", bonus: String = "14", extra: String = "") = """
        <html><body><table><thead><tr><th>Date</th><th>Powerball</th></tr></thead><tbody>
        <tr><td>$dateText</td><td>${values.split(' ').joinToString("") { "<span>$it</span>" }} <span class="font-bold color-powerball">$bonus</span> $extra</td></tr>
        </tbody></table></body></html>
    """.trimIndent()
    private fun delawareMegaHtml(dateText: String = "9/2/2026") = """
        <html><body><table><thead><tr><th>Date</th><th>Mega Millions</th></tr></thead><tbody>
        <tr><td>$dateText</td><td><span>01</span><span>22</span><span>51</span><span>61</span><span>63</span><span class="font-bold color-megamillions">17</span></td></tr>
        </tbody></table></body></html>
    """.trimIndent()
    private fun muslHtml(dateText: String = "2026-09-02", values: String = "2,4,16,39,45,6") = """
        <html><body><input id="DrawDate" value="$dateText" />
        <h1>Lotto America</h1><div class="number-group-lotto-america">
        ${values.split(',').joinToString("") { "<span class=\"item-lotto-america\">$it</span>" }}
        </div><div class="all-star-bonus"><span class="multiplier">3x</span></div></body></html>
    """.trimIndent()
    private fun mixedDelawareHtml() = """
        <html><body>
        ${delawareHtml()}
        ${delawareMegaHtml()}
        </body></html>
    """.trimIndent()
    private fun doublePlayOnlyHtml() = """
        <html><body><table><thead><tr><th>Date</th><th>Powerball Double Play</th></tr></thead><tbody>
        <tr><td>9/2/2026</td><td>3 10 29 58 64 14</td></tr>
        </tbody></table></body></html>
    """.trimIndent()

    @Test fun nyPowerballParsesExactSixNumbers() {
        val result = NyPowerballCurrentDrawSource(http("[{\"draw_date\":\"2026-09-02T00:00:00.000\",\"winning_numbers\":\"3 10 29 58 64 14\",\"multiplier\":\"2\"}]")).fetch(date, now)
        val found = assertIs<OfficialDrawSourceResult.Found>(result).observation
        assertEquals(listOf(3, 10, 29, 58, 64), found.mainValues); assertEquals(listOf(14), found.bonusValues)
    }

    @Test fun nyPowerballParsesMultiplierAsMetadata() {
        val result = NyPowerballCurrentDrawSource(http("[{\"draw_date\":\"2026-09-02\",\"winning_numbers\":\"3 10 29 58 64 14\",\"multiplier\":\"2\"}]")).fetch(date, now)
        assertEquals("2", assertIs<OfficialDrawSourceResult.Found>(result).observation.drawMetadata["multiplier"])
    }

    @Test fun nyMegaMillionsParsesFivePlusMegaBall() {
        val r = NyMegaMillionsCurrentDrawSource(http("[{\"draw_date\":\"2026-09-02\",\"winning_numbers\":\"1 22 51 61 63\",\"mega_ball\":\"17\",\"multiplier\":\"-1\"}]")).fetch(date, now)
        val o = assertIs<OfficialDrawSourceResult.Found>(r).observation
        assertEquals(listOf(1, 22, 51, 61, 63), o.mainValues); assertEquals(listOf(17), o.bonusValues)
    }

    @Test fun nyMegaMinusOneMultiplierDoesNotInvalidateDraw() {
        val r = NyMegaMillionsCurrentDrawSource(http("[{\"draw_date\":\"2026-09-02\",\"winning_numbers\":\"1 22 51 61 63\",\"mega_ball\":\"17\",\"multiplier\":\"-1\"}]")).fetch(date, now)
        assertTrue(r is OfficialDrawSourceResult.Found)
    }

    @Test fun delawarePowerballExcludesDoublePlayNumbers() {
        val html = delawareHtml(extra = "<span class=\"color-primary\">2</span> <span class=\"double-play\">1 2 3 4 5 6</span>")
        val o = assertIs<OfficialDrawSourceResult.Found>(DelawarePowerballCurrentDrawSource(http(html)).fetch(date, now)).observation
        assertEquals(listOf(3, 10, 29, 58, 64), o.mainValues); assertEquals(listOf(14), o.bonusValues)
    }

    @Test fun delawarePowerballExtractsOptionalPowerPlayMetadata() {
        val html = delawareHtml(extra = "<span class=\"color-primary\">2</span>")
        val o = assertIs<OfficialDrawSourceResult.Found>(DelawarePowerballCurrentDrawSource(http(html)).fetch(date, now)).observation
        assertEquals(listOf(3, 10, 29, 58, 64), o.mainValues)
    }

    @Test fun delawareMegaMillionsParsesFivePlusMegaBall() {
        val html = delawareMegaHtml()
        val o = assertIs<OfficialDrawSourceResult.Found>(DelawareMegaMillionsCurrentDrawSource(http(html)).fetch(date, now)).observation
        assertEquals(listOf(1, 22, 51, 61, 63), o.mainValues); assertEquals(listOf(17), o.bonusValues)
    }
    @Test fun delawareDateAbsentReturnsNotYetPublished() { assertTrue(DelawarePowerballCurrentDrawSource(http(delawareHtml(dateText = "9/1/2026"))).fetch(date, now) is OfficialDrawSourceResult.NotYetPublished) }
    @Test fun delawareRequestedDatePresentButMalformedReturnsUnavailable() { assertTrue(DelawarePowerballCurrentDrawSource(http("<table><tr><th>Date</th><th>Powerball</th></tr><tr><td>9/2/2026</td><td>unclear</td></tr></table>")).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun delawareDuplicateRequestedDateFailsClosed() {
        val html = delawareHtml() .replace("</tbody>", "<tr><td>9/2/2026</td><td>03 10 29 58 64 <span class=\"color-powerball\">14</span></td></tr></tbody>")
        assertTrue(DelawarePowerballCurrentDrawSource(http(html)).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawareConflictingDuplicateRequestedDateFailsClosed() {
        val html = delawareHtml() .replace("</tbody>", "<tr><td>9/2/2026</td><td>01 02 03 04 05 <span class=\"color-powerball\">06</span></td></tr></tbody>")
        assertTrue(DelawarePowerballCurrentDrawSource(http(html)).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawarePowerballExcludesPowerPlayFromCanonicalNumbers() {
        val o = assertIs<OfficialDrawSourceResult.Found>(DelawarePowerballCurrentDrawSource(http(delawareHtml(extra = "<span class=\"color-primary\">2</span>"))).fetch(date, now)).observation
        assertEquals(listOf(3,10,29,58,64), o.mainValues); assertEquals(listOf(14), o.bonusValues)
    }
    @Test fun delawareMegaMillionsParsesOnlyMainAndMegaBall() {
        val o = assertIs<OfficialDrawSourceResult.Found>(DelawareMegaMillionsCurrentDrawSource(http(delawareMegaHtml())).fetch(date, now)).observation
        assertEquals(listOf(1,22,51,61,63), o.mainValues); assertEquals(listOf(17), o.bonusValues)
    }
    @Test fun delawarePowerballRejectsMegaMillionsResultPage() {
        assertTrue(DelawarePowerballCurrentDrawSource(http(delawareMegaHtml())).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawareMegaMillionsRejectsPowerballResultPage() {
        assertTrue(DelawareMegaMillionsCurrentDrawSource(http(delawareHtml())).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawarePowerballWrongGamePageWithRequestedDateAbsentIsUnavailable() {
        assertTrue(DelawarePowerballCurrentDrawSource(http(delawareMegaHtml("9/1/2026"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawareMegaMillionsWrongGamePageWithRequestedDateAbsentIsUnavailable() {
        val megaDate = LocalDate.parse("2026-09-01")
        assertTrue(DelawareMegaMillionsCurrentDrawSource(http(delawareHtml("9/2/2026"))).fetch(megaDate, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawarePowerballNoRecognizedGameTableIsUnavailable() {
        assertTrue(DelawarePowerballCurrentDrawSource(http("<html><p>9/2/2026 3 10 29 58 64 14</p></html>")).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawarePowerballTwoCanonicalTablesFailClosed() {
        assertTrue(DelawarePowerballCurrentDrawSource(http(delawareHtml() + delawareHtml("9/1/2026"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawareMegaMillionsTwoCanonicalTablesFailClosed() {
        assertTrue(DelawareMegaMillionsCurrentDrawSource(http(delawareMegaHtml() + delawareMegaHtml("9/1/2026"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawarePowerballDoublePlayTableCannotBecomeCanonicalResult() {
        assertTrue(DelawarePowerballCurrentDrawSource(http(doublePlayOnlyHtml())).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun delawarePowerballCanonicalTablePlusDoublePlayTableSelectsCanonicalOnly() {
        val html = delawareHtml() + doublePlayOnlyHtml()
        val observation = assertIs<OfficialDrawSourceResult.Found>(DelawarePowerballCurrentDrawSource(http(html)).fetch(date, now)).observation
        assertEquals(listOf(3, 10, 29, 58, 64), observation.mainValues)
        assertEquals(listOf(14), observation.bonusValues)
    }
    @Test fun delawareRecognizedPowerballTableDateAbsentReturnsNotYetPublished() {
        assertTrue(DelawarePowerballCurrentDrawSource(http(delawareHtml("9/1/2026"))).fetch(date, now) is OfficialDrawSourceResult.NotYetPublished)
    }
    @Test fun delawareRecognizedMegaMillionsTableDateAbsentReturnsNotYetPublished() {
        assertTrue(DelawareMegaMillionsCurrentDrawSource(http(delawareMegaHtml("9/1/2026"))).fetch(date, now) is OfficialDrawSourceResult.NotYetPublished)
    }
    @Test fun delawarePowerballSelectsOnlyPowerballTableFromMixedPage() {
        val observation = assertIs<OfficialDrawSourceResult.Found>(DelawarePowerballCurrentDrawSource(http(mixedDelawareHtml())).fetch(date, now)).observation
        assertEquals(listOf(3, 10, 29, 58, 64), observation.mainValues)
        assertEquals(listOf(14), observation.bonusValues)
    }
    @Test fun delawareMegaMillionsSelectsOnlyMegaMillionsTableFromMixedPage() {
        val observation = assertIs<OfficialDrawSourceResult.Found>(DelawareMegaMillionsCurrentDrawSource(http(mixedDelawareHtml())).fetch(date, now)).observation
        assertEquals(listOf(1, 22, 51, 61, 63), observation.mainValues)
        assertEquals(listOf(17), observation.bonusValues)
    }

    @Test fun muslLottoAmericaParsesFivePlusStarBall() {
        val html = muslHtml()
        val o = assertIs<OfficialDrawSourceResult.Found>(MuslLottoAmericaCurrentDrawSource(http(html)).fetch(date, now)).observation
        assertEquals(listOf(2, 4, 16, 39, 45), o.mainValues); assertEquals(listOf(6), o.bonusValues)
    }
    @Test fun muslRequestedDateResultParsesExactly() { assertTrue(MuslLottoAmericaCurrentDrawSource(http(muslHtml())).fetch(date, now) is OfficialDrawSourceResult.Found) }
    @Test fun muslWrongVisibleDrawDateFailsClosed() { assertTrue(MuslLottoAmericaCurrentDrawSource(http(muslHtml("2026-09-03"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun muslMissingDateIdentityFailsClosed() { assertTrue(MuslLottoAmericaCurrentDrawSource(http(muslHtml().replace("<input id=\"DrawDate\" value=\"2026-09-02\" />", ""))).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun muslMalformedCanonicalNumberContainerFailsClosed() { assertTrue(MuslLottoAmericaCurrentDrawSource(http(muslHtml(values = "2,4,16"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun muslWholePageUnrelatedNumbersCannotBecomeResult() { assertTrue(MuslLottoAmericaCurrentDrawSource(http("<html>Lotto America 1 2 3 4 5 6</html>")).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun muslAllStarBonusRemainsMetadataOnly() { val o = assertIs<OfficialDrawSourceResult.Found>(MuslLottoAmericaCurrentDrawSource(http(muslHtml())).fetch(date, now)).observation; assertEquals(listOf(2,4,16,39,45), o.mainValues); assertEquals(listOf(6), o.bonusValues); assertEquals("3x", o.drawMetadata["allStarBonus"]) }

    @Test fun iowaLottoAmericaParsesRequestedDateOnly() {
        val html = iowaHtml()
        val o = assertIs<OfficialDrawSourceResult.Found>(IowaLottoAmericaCurrentDrawSource(http(html)).fetch(date, now)).observation
        assertEquals(listOf(2, 4, 16, 39, 45), o.mainValues); assertEquals(listOf(6), o.bonusValues)
    }

    @Test fun iowaLottoAmericaIgnoresGlobalPowerballBlockAndParsesScopedTable() {
        val o = assertIs<OfficialDrawSourceResult.Found>(IowaLottoAmericaCurrentDrawSource(http(iowaHtml())).fetch(date, now)).observation
        assertEquals(listOf(2, 4, 16, 39, 45), o.mainValues)
        assertEquals(listOf(6), o.bonusValues)
        assertEquals("3", o.drawMetadata["allStarBonus"])
    }

    @Test fun iowaLottoAmericaReturnsNotYetPublishedWhenDateAbsentFromValidTable() {
        val result = IowaLottoAmericaCurrentDrawSource(http(iowaHtml().replace("9/2/2026", "9/3/2026"))).fetch(date, now)
        assertTrue(result is OfficialDrawSourceResult.NotYetPublished)
    }

    @Test fun iowaLottoAmericaWrongGamePageFailsClosed() {
        val result = IowaLottoAmericaCurrentDrawSource(http("<html><table><tr><th>Date</th><th>Numbers</th><th>All Star Bonus</th></tr></table></html>")).fetch(date, now)
        assertTrue(result is OfficialDrawSourceResult.Unavailable)
    }

    @Test fun iowaLottoAmericaDuplicateDateFailsClosed() {
        val html = iowaHtml() .replace("</tbody>", "<tr><td>9/2/2026</td><td>2 - 4 - 16 - 39 - 45 - 6</td><td>3</td></tr></tbody>")
        assertTrue(IowaLottoAmericaCurrentDrawSource(http(html)).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }

    @Test fun iowaLottoAmericaMalformedNumberCountFailsClosed() {
        assertTrue(IowaLottoAmericaCurrentDrawSource(http(iowaHtml("2 - 4 - 16 - 39 - 45"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
    @Test fun iowaLottoAmericaRejectsFiveNumbers() { assertTrue(IowaLottoAmericaCurrentDrawSource(http(iowaHtml("2 - 4 - 16 - 39 - 45"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun iowaLottoAmericaRejectsSevenNumbers() { assertTrue(IowaLottoAmericaCurrentDrawSource(http(iowaHtml("2 - 4 - 16 - 39 - 45 - 6 - 7"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun iowaLottoAmericaRejectsEightNumbers() { assertTrue(IowaLottoAmericaCurrentDrawSource(http(iowaHtml("2 - 4 - 16 - 39 - 45 - 6 - 7 - 8"))).fetch(date, now) is OfficialDrawSourceResult.Unavailable) }
    @Test fun iowaLottoAmericaAcceptsExactlySixNumbers() { assertTrue(IowaLottoAmericaCurrentDrawSource(http(iowaHtml())).fetch(date, now) is OfficialDrawSourceResult.Found) }

    @Test fun iowaLottoAmericaAllStarBonusIsMetadataOnly() {
        val o = assertIs<OfficialDrawSourceResult.Found>(IowaLottoAmericaCurrentDrawSource(http(iowaHtml())).fetch(date, now)).observation
        assertEquals(listOf(2, 4, 16, 39, 45), o.mainValues); assertEquals(listOf(6), o.bonusValues); assertEquals("3", o.drawMetadata["allStarBonus"])
    }

    @Test fun iowaLottoAmericaWhitespaceAndNbspDoNotBreakHeaderDetection() {
        val html = iowaHtml().replace("All Star Bonus", " All&nbsp;Star  Bonus ")
        assertTrue(IowaLottoAmericaCurrentDrawSource(http(html)).fetch(date, now) is OfficialDrawSourceResult.Found)
    }

    @Test fun malformedHtmlFailsClosed() {
        assertTrue(DelawarePowerballCurrentDrawSource(http("<html>09/02/2026 unclear</html>")).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }

    @Test fun duplicateRequestedDateFailsClosed() {
        val json = "[{\"draw_date\":\"2026-09-02\",\"winning_numbers\":\"3 10 29 58 64 14\"},{\"draw_date\":\"2026-09-02\",\"winning_numbers\":\"3 10 29 58 64 14\"}]"
        assertTrue(NyPowerballCurrentDrawSource(http(json)).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }

    @Test fun outOfRangeNumbersFailClosed() {
        val json = "[{\"draw_date\":\"2026-09-02\",\"winning_numbers\":\"3 10 29 58 64 27\"}]"
        assertTrue(NyPowerballCurrentDrawSource(http(json)).fetch(date, now) is OfficialDrawSourceResult.Unavailable)
    }
}

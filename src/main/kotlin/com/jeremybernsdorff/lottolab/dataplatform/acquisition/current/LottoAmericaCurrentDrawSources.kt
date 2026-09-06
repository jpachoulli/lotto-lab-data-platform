package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

private val iowaDateFormatter = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US)
    .withResolverStyle(ResolverStyle.STRICT)

private fun normalizeWhitespace(value: String): String = value
    .replace('\u00a0', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()

private data class IowaTableRow(val date: LocalDate, val main: List<Int>, val starBall: Int, val allStarBonus: String?)

private fun parseIowaTable(html: String, requestedDate: LocalDate): Pair<IowaTableRow?, Int>? {
    val document = Jsoup.parse(html)
    if (!document.text().contains("Lotto America", ignoreCase = true)) return null
    val tables = document.select("table").filter { table ->
        val headers = table.select("th").map { normalizeWhitespace(it.text()).lowercase(Locale.US) }
        val headerSemantics = headers.joinToString("|")
        headerSemantics.contains("date") && headerSemantics.contains("numbers") && headerSemantics.contains("all star bonus")
    }
    require(tables.size == 1) { "expected exactly one Lotto America result table, found ${tables.size}" }
    val rows = tables.single().select("tr").mapNotNull { row ->
        val cells = row.select("td")
        if (cells.size < 2) return@mapNotNull null
        val parsedDate = runCatching { LocalDate.parse(normalizeWhitespace(cells[0].text()), iowaDateFormatter) }.getOrNull()
            ?: return@mapNotNull null
        val semanticNumberSpans = cells[1].select("span[id*='WinningNumbersRowLA']").filterNot { it.id().contains("MP") }
        val numberCellValues = if (semanticNumberSpans.isNotEmpty()) {
            semanticNumberSpans.flatMap { Regex("\\b\\d{1,2}\\b").findAll(it.text()).map { match -> match.value.toInt() }.toList() }
        } else {
            Regex("\\b\\d{1,2}\\b").findAll(normalizeWhitespace(cells[1].text())).map { it.value.toInt() }.toList()
        }
        if (numberCellValues.size != 6) throw IllegalArgumentException("Iowa Lotto America numbers cell must contain exactly six integers")
        val values = numberCellValues
        val asbText = if (cells.size >= 3) normalizeWhitespace(cells[2].text()) else {
            cells[1].select("span[id*='WinningNumbersRowLA'][id*='MP']").firstOrNull()?.text()?.trim().orEmpty()
        }
        val asb = if (asbText.isBlank()) null else asbText.toIntOrNull()?.toString()
            ?: throw IllegalArgumentException("invalid All Star Bonus")
        IowaTableRow(parsedDate, values.take(5), values[5], asb)
    }
    val matches = rows.filter { it.date == requestedDate }
    return matches.singleOrNull() to matches.size
}

private fun iowaFetch(http: OfficialDrawHttpClient, date: LocalDate, retrieved: Instant): OfficialDrawSourceResult {
    val url = "https://www.ialottery.com/pages/Games-Online/LottoAmericaWin.aspx"
    return try {
        val response = http.get(url)
        val parsed = parseIowaTable(String(response.body, Charsets.UTF_8), date)
            ?: return OfficialDrawSourceResult.Unavailable("Iowa page is not identifiable as Lotto America")
        val row = parsed.first ?: return if (parsed.second == 0) OfficialDrawSourceResult.NotYetPublished else OfficialDrawSourceResult.Unavailable("ambiguous Iowa requested date")
        val metadata = row.allStarBonus?.let { mapOf("allStarBonus" to it) } ?: emptyMap()
        sourceResult("iowa_lottery_lotto_america", "Iowa Lottery", "lotto_america", url, date, retrieved, response, row.main, listOf(row.starBall), metadata)
    } catch (e: Exception) { OfficialDrawSourceResult.Unavailable("Iowa Lotto America unavailable: ${e.message ?: e::class.simpleName}") }
}

private fun muslFetch(http: OfficialDrawHttpClient, date: LocalDate, retrieved: Instant): OfficialDrawSourceResult {
    val url = "https://www.powerball.com/draw-result?date=$date&gc=lotto-america"
    return try {
        val response = http.get(url)
        val document = Jsoup.parse(String(response.body, Charsets.UTF_8))
        val visibleDrawDates = document.select("#DrawDate").mapNotNull { it.attr("value").trim().takeIf(String::isNotBlank) }.distinct()
        if (visibleDrawDates.size != 1 || visibleDrawDates.single() != date.toString()) {
            return OfficialDrawSourceResult.Unavailable("MUSL requested draw date is not unambiguously identified")
        }
        val scopedValues = document.select(".number-group-lotto-america .item-lotto-america")
            .mapNotNull { it.text().trim().toIntOrNull() }
        if (scopedValues.size != 6) return OfficialDrawSourceResult.Unavailable("MUSL Lotto America canonical number container is malformed")
        val values = scopedValues
        val metadata = document.selectFirst(".all-star-bonus .multiplier")?.text()?.trim()?.let { mapOf("allStarBonus" to it) } ?: emptyMap()
        sourceResult("musl_lotto_america", "Multi-State Lottery Association", "lotto_america", url, date, retrieved, response, values.take(5), listOf(values[5]), metadata)
    } catch (e: Exception) { OfficialDrawSourceResult.Unavailable("MUSL Lotto America unavailable: ${e.message ?: e::class.simpleName}") }
}

class MuslLottoAmericaCurrentDrawSource(private val http: OfficialDrawHttpClient) : OfficialDrawSource {
    override val gameId = "lotto_america"
    override val sourceId = "musl_lotto_america"
    override fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant) = muslFetch(http, drawDate, retrievedAtUtc)
}

class IowaLottoAmericaCurrentDrawSource(private val http: OfficialDrawHttpClient) : OfficialDrawSource {
    override val gameId = "lotto_america"
    override val sourceId = "iowa_lottery_lotto_america"
    override fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant) = iowaFetch(http, drawDate, retrievedAtUtc)
}

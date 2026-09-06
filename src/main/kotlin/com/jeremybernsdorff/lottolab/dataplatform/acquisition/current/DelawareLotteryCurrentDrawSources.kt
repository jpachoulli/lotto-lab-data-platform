package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.Month

private fun monthName(month: Month) = month.name.lowercase().replaceFirstChar { it.uppercase() }

private sealed interface DelawareParseResult {
    data object RequestedDateAbsent : DelawareParseResult
    data class Parsed(val mainValues: List<Int>, val bonusValues: List<Int>, val metadata: Map<String, String>) : DelawareParseResult
    data class Malformed(val reason: String) : DelawareParseResult
}

private fun requestedDateText(date: LocalDate): Regex = Regex(
    "${date.monthValue}\\s*/\\s*${date.dayOfMonth}\\s*/\\s*${date.year}|" +
        "${monthName(date.month)}\\s+${date.dayOfMonth},?\\s+${date.year}|" +
        "${date.monthValue.toString().padStart(2, '0')}[-/]${date.dayOfMonth.toString().padStart(2, '0')}[-/]${date.year}",
    RegexOption.IGNORE_CASE
)

private fun rowDate(cell: Element, date: LocalDate): Boolean = requestedDateText(date).containsMatchIn(cell.text())

private fun normalizeHeader(value: String): String = value
    .replace('\u00a0', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()
    .lowercase()

private fun tableMatchesRequestedGame(table: Element, game: String): Boolean {
    val headers = table.select("th").map { normalizeHeader(it.text()) }
    if (headers.none { it == "date" || it == "draw date" }) return false
    return when (game) {
        "powerball" -> headers.any { it == "powerball" }
        "mega_millions" -> headers.any { it == "mega millions" || it == "megamillions" }
        else -> false
    }
}

private fun numericSpans(cell: Element): List<Pair<Int, String>> = cell.children().takeWhile { it.tagName() != "br" }.filter { it.tagName() == "span" }.mapNotNull { span ->
    Regex("\\b\\d{1,2}\\b").find(span.text())?.value?.toIntOrNull()?.let { it to span.classNames().joinToString(" ") }
}

private fun parseDelawareTable(html: String, date: LocalDate, game: String): DelawareParseResult {
    val document = Jsoup.parse(html)
    val tables = document.select("table").filter { table -> tableMatchesRequestedGame(table, game) }
    if (tables.isEmpty()) return DelawareParseResult.Malformed("requested Delaware game result table not found")
    if (tables.size > 1) return DelawareParseResult.Malformed("ambiguous Delaware game result tables")
    val candidateRows = tables.flatMap { table -> table.select("tr").filter { row -> row.select("td").firstOrNull()?.let { rowDate(it, date) } == true } }
    if (candidateRows.isEmpty()) {
        return DelawareParseResult.RequestedDateAbsent
    }
    if (candidateRows.size != 1) return DelawareParseResult.Malformed("ambiguous requested Delaware date")
    val cells = candidateRows.single().select("td")
    if (cells.size < 2) return DelawareParseResult.Malformed("requested Delaware row has no result cell")
    val spans = numericSpans(cells[1])
    val values = if (spans.isNotEmpty()) spans else Regex("\\b\\d{1,2}\\b").findAll(cells[1].text()).map { it.value to "" }.map { it.first.toInt() to it.second }.toList()
    val canonical = if (game == "powerball") values.filterNot {
        val classes = it.second.split(' ')
        classes.contains("color-primary") || classes.any { className -> className.contains("double", ignoreCase = true) }
    } else values
    if (canonical.size != 6) return DelawareParseResult.Malformed("requested Delaware canonical result is ambiguous")
    val metadata = if (game == "powerball") values.firstOrNull { it.second.split(' ').contains("color-primary") }?.first?.toString()?.let { mapOf("powerPlay" to it) } ?: emptyMap() else emptyMap()
    return DelawareParseResult.Parsed(canonical.take(5).map { it.first }, listOf(canonical[5].first), metadata)
}

private fun delawareFetch(http: OfficialDrawHttpClient, game: String, sourceId: String, date: LocalDate, retrieved: Instant, path: String): OfficialDrawSourceResult {
    val url = "https://www.delottery.com/Winning-Numbers/Search-Winners-Printable/${date.year}/${date.monthValue}/$path"
    return try {
        val response = http.get(url)
        when (val parsed = parseDelawareTable(String(response.body, Charsets.UTF_8), date, game)) {
            DelawareParseResult.RequestedDateAbsent -> OfficialDrawSourceResult.NotYetPublished
            is DelawareParseResult.Malformed -> OfficialDrawSourceResult.Unavailable(parsed.reason)
            is DelawareParseResult.Parsed -> sourceResult(sourceId, "Delaware Lottery", game, url, date, retrieved, response, parsed.mainValues, parsed.bonusValues, parsed.metadata)
        }
    } catch (e: Exception) { OfficialDrawSourceResult.Unavailable("Delaware source unavailable: ${e.message ?: e::class.simpleName}") }
}

class DelawarePowerballCurrentDrawSource(private val http: OfficialDrawHttpClient) : OfficialDrawSource {
    override val gameId = "powerball"
    override val sourceId = "delaware_lottery_powerball"
    override fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant) = delawareFetch(http, gameId, sourceId, drawDate, retrievedAtUtc, "PowerBall")
}

class DelawareMegaMillionsCurrentDrawSource(private val http: OfficialDrawHttpClient) : OfficialDrawSource {
    override val gameId = "mega_millions"
    override val sourceId = "delaware_lottery_mega_millions"
    override fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant) = delawareFetch(http, gameId, sourceId, drawDate, retrievedAtUtc, "MegaMillions")
}

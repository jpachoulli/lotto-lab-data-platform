package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate

private val mapper = ObjectMapper()

private fun encoded(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun parseNumbers(value: String?): List<Int>? = value?.trim()?.split(Regex("[ ,|]+"))
    ?.filter { it.isNotBlank() }
    ?.mapNotNull { it.toIntOrNull() }
    ?.takeIf { it.size == value.trim().split(Regex("[ ,|]+")).count { s -> s.isNotBlank() } }

internal fun sourceResult(
    sourceId: String,
    organization: String,
    gameId: String,
    url: String,
    date: LocalDate,
    retrieved: Instant,
    response: OfficialHttpResponse,
    main: List<Int>,
    bonus: List<Int>,
    metadata: Map<String, String> = emptyMap()
): OfficialDrawSourceResult {
    validateCurrentNumbers(gameId, date, main, bonus)?.let { return OfficialDrawSourceResult.Unavailable(it) }
    return OfficialDrawSourceResult.Found(OfficialDrawObservation(gameId, date, main.toList(), bonus.toList(), sourceId, organization, url, retrieved, response.rawSha256, metadata.toMap()))
}

class NyPowerballCurrentDrawSource(private val http: OfficialDrawHttpClient) : OfficialDrawSource {
    override val gameId = "powerball"
    override val sourceId = "ny_gaming_commission_powerball"
    override fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant): OfficialDrawSourceResult {
        val url = "https://data.ny.gov/resource/d6yy-54nr.json?draw_date=${encoded(drawDate.toString())}"
        return try {
            val response = http.get(url)
            val rows = mapper.readTree(response.body)
            if (!rows.isArray) return OfficialDrawSourceResult.Unavailable("unexpected Socrata shape")
            val matching = rows.filter { it.path("draw_date").asText().take(10) == drawDate.toString() }
            when {
                matching.isEmpty() -> OfficialDrawSourceResult.NotYetPublished
                matching.size != 1 -> OfficialDrawSourceResult.Unavailable("ambiguous requested date")
                else -> {
                    val values = parseNumbers(matching.single().path("winning_numbers").asText())
                        ?: return OfficialDrawSourceResult.Unavailable("malformed winning_numbers")
                    if (values.size != 6) return OfficialDrawSourceResult.Unavailable("Powerball requires six values")
                    sourceResult(sourceId, "New York State Gaming Commission", gameId, url, drawDate, retrievedAtUtc, response, values.take(5), listOf(values[5]), mapOf("multiplier" to matching.single().path("multiplier").asText()))
                }
            }
        } catch (e: Exception) { OfficialDrawSourceResult.Unavailable("Powerball primary unavailable: ${e.message ?: e::class.simpleName}") }
    }
}

class NyMegaMillionsCurrentDrawSource(private val http: OfficialDrawHttpClient) : OfficialDrawSource {
    override val gameId = "mega_millions"
    override val sourceId = "ny_gaming_commission_mega_millions"
    override fun fetch(drawDate: LocalDate, retrievedAtUtc: Instant): OfficialDrawSourceResult {
        val url = "https://data.ny.gov/resource/5xaw-6ayf.json?draw_date=${encoded(drawDate.toString())}"
        return try {
            val response = http.get(url)
            val rows = mapper.readTree(response.body)
            if (!rows.isArray) return OfficialDrawSourceResult.Unavailable("unexpected Socrata shape")
            val matching = rows.filter { it.path("draw_date").asText().take(10) == drawDate.toString() }
            when {
                matching.isEmpty() -> OfficialDrawSourceResult.NotYetPublished
                matching.size != 1 -> OfficialDrawSourceResult.Unavailable("ambiguous requested date")
                else -> {
                    val row = matching.single()
                    val main = parseNumbers(row.path("winning_numbers").asText()) ?: return OfficialDrawSourceResult.Unavailable("malformed winning_numbers")
                    val bonus = row.path("mega_ball").asText().toIntOrNull() ?: return OfficialDrawSourceResult.Unavailable("malformed mega_ball")
                    sourceResult(sourceId, "New York State Gaming Commission", gameId, url, drawDate, retrievedAtUtc, response, main, listOf(bonus), mapOf("multiplier" to row.path("multiplier").asText()))
                }
            }
        } catch (e: Exception) { OfficialDrawSourceResult.Unavailable("Mega Millions primary unavailable: ${e.message ?: e::class.simpleName}") }
    }
}

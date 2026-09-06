package com.jeremybernsdorff.lottolab.dataplatform.acquisition.current

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

data class OfficialHttpResponse(val url: String, val statusCode: Int, val body: ByteArray, val rawSha256: String)

class OfficialDrawHttpClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val fetcher: ((String) -> OfficialHttpResponse)? = null
) {
    fun get(url: String): OfficialHttpResponse {
        require(url.startsWith("https://"))
        fetcher?.let { return it(url) }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "LottoLabDataPlatform/0.1 (+https://github.com/jpachoulli/lotto-lab-data-platform)")
            .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.1")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) error("HTTP ${response.statusCode()} from $url")
        if (response.body().size > 2 * 1024 * 1024) error("response exceeds 2 MiB")
        val bytes = response.body()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return OfficialHttpResponse(url, response.statusCode(), bytes, digest)
    }
}

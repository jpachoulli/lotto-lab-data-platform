package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

object StrictCsvCodec {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var closedQuote = false; var i = 0
        fun finishField() { row += field.toString(); field.clear(); closedQuote = false }
        fun finishRow() { finishField(); rows += row.toList(); row.clear() }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when (c) {
                    '"' -> if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else { quoted = false; closedQuote = true }
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> { require(field.isEmpty()) { "quote inside unquoted CSV field" }; require(!closedQuote) { "content after closing quote" }; quoted = true }
                    ',' -> { finishField() }
                    '\n' -> { require(!closedQuote || field.isNotEmpty() || row.isNotEmpty()); finishRow() }
                    '\r' -> { finishRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                    else -> { require(!closedQuote) { "content after closing quote" }; field.append(c) }
                }
            }
            i++
        }
        require(!quoted) { "unterminated CSV quote" }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) finishRow()
        val width = rows.firstOrNull()?.size
        require(rows.all { it.size == width }) { "CSV row width mismatch" }
        return rows
    }
    fun write(rows: List<List<String>>): ByteArray {
        require(rows.isEmpty() || rows.all { it.size == rows.first().size })
        fun esc(v: String) = if (v.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v
        return rows.joinToString("\n") { it.joinToString(",", transform = ::esc) }.plus("\n").toByteArray(Charsets.UTF_8)
    }
}

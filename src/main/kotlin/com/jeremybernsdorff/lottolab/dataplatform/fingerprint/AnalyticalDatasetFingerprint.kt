package com.jeremybernsdorff.lottolab.dataplatform.fingerprint

import com.jeremybernsdorff.lottolab.dataplatform.model.CorpusDrawRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object AnalyticalDatasetFingerprint {
    fun normalizedSession(session: String?): String = session?.trim()?.uppercase(Locale.US).orEmpty()

    fun compute(records: List<CorpusDrawRecord>): String {
        val canonical = records.sortedWith(
            compareBy<CorpusDrawRecord>({ it.gameId }, { it.eraId }, { it.drawDate }, { normalizedSession(it.drawSession) })
                .thenComparator { left, right -> compareLists(left.mainValues, right.mainValues) }
                .thenComparator { left, right -> compareLists(left.bonusValues, right.bonusValues) }
        ).joinToString("\n") { record ->
            val session = normalizedSession(record.drawSession)
            listOfNotNull(
                record.gameId,
                record.eraId,
                record.drawDate.toString(),
                if (session.isEmpty()) null else "SESSION=$session",
                record.mainValues.joinToString(","),
                record.bonusValues.joinToString(",")
            ).joinToString("|")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun compareLists(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            left[index].compareTo(right[index]).takeIf { it != 0 }?.let { return it }
        }
        return left.size.compareTo(right.size)
    }
}

package cn.edu.ahtcm.schedule.core

import java.time.LocalDate

class NoticeParser {
    fun parse(records: List<RawNotice>): List<AdjustmentNotice> = records
        .filter { it.text.contains("调课") || it.text.contains("停课") || it.text.contains("补课") }
        .distinctBy { it.id.ifBlank { it.text + it.publishedAt } }
        .groupBy { it.text.replace(Regex("\\s+"), "") }
        .values.map { duplicates ->
            val latest = duplicates.maxBy { it.publishedAt }
            fun date(pattern: Regex): String? = pattern.find(latest.text)?.groupValues?.get(1)
                ?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
            AdjustmentNotice(
                latest.text, latest.publishedAt, duplicates.size,
                date(Regex("原定于\\s*(\\d{4}-\\d{2}-\\d{2})")),
                date(Regex("现调整为\\s*(\\d{4}-\\d{2}-\\d{2})"))
            )
        }.sortedByDescending { it.publishedAt }
}

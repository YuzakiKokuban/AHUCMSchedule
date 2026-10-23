package cn.edu.ahtcm.schedule.core

import java.time.LocalTime
import kotlinx.serialization.json.*

internal fun validatePeriods(periods: List<ClassPeriod>) {
    require(periods.map { it.section }.sorted() == (1..14).toList())
    periods.forEach {
        require(Regex("\\d{2}:\\d{2}").matches(it.start) && Regex("\\d{2}:\\d{2}").matches(it.end))
        require(LocalTime.parse(it.start) < LocalTime.parse(it.end))
    }
}

internal object SchoolPeriodParser {
    fun parse(html: String): List<ClassPeriod> = try {
        val data = Regex("businessHours\\s*=\\s*\\$\\.parseJSON\\(\\s*'([^']*)'", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Missing businessHours")
        val periods = Json.parseToJsonElement(data).jsonArray.map { row ->
            val item = row.jsonObject
            fun time(key: String) = LocalTime.parse(item.getValue(key).jsonPrimitive.content).toString().take(5)
            ClassPeriod(item.getValue("jcdm").jsonPrimitive.content.toInt(), time("qssj"), time("jssj"))
        }.sortedBy { it.section }
        validatePeriods(periods)
        periods
    } catch (_: Exception) { throw SchoolException("未获取到完整作息时间，请重新同步") }
}

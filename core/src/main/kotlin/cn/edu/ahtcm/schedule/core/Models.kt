package cn.edu.ahtcm.schedule.core

import kotlinx.serialization.Serializable

@Serializable
data class Lesson(
    val name: String, val kind: String, val teachers: String, val location: String,
    val content: String, val date: String, val week: Int, val sections: List<Int>
)

@Serializable
data class ScheduleSnapshot(
    val termCode: String, val termLabel: String, val weekOneMonday: String,
    val retrievedAt: String, val lessons: List<Lesson>,
    val calendarEndDate: String = "", val source: String = "file",
    val periods: List<ClassPeriod> = emptyList()
)

@Serializable
data class ClassPeriod(val section: Int, val start: String, val end: String)

data class SchoolTimetable(val xls: ByteArray, val periods: List<ClassPeriod>, val currentTermCode: String? = null)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface UpdateResult {
    data class Success(val snapshot: ScheduleSnapshot) : UpdateResult
    data class Failure(val message: String) : UpdateResult
}

@Serializable
data class RawNotice(val id: String, val text: String, val publishedAt: String)

@Serializable
data class AdjustmentNotice(
    val text: String, val publishedAt: String, val duplicateCount: Int,
    val originalDate: String?, val newDate: String?
)

package cn.edu.ahtcm.schedule.ui

import cn.edu.ahtcm.schedule.core.*
import kotlinx.coroutines.*

internal fun termFor(code: String): AcademicTerm? = runCatching {
    val year = code.take(4).toInt()
    AcademicTerm(code, "$year-${year + 1}-${code.last()}")
}.getOrNull()

internal fun knownTerms(platform: PlatformActions): List<AcademicTerm> = (
    platform.academicCalendarStore?.availableTerms()?.terms.orEmpty() +
        platform.repository.snapshots().mapNotNull { termFor(it.termCode) } +
        platform.academicCalendarStore?.snapshots().orEmpty().map { it.term } +
        listOfNotNull(platform.termSelection?.selected())
    ).distinctBy { it.code }.sortedByDescending { it.code }

internal data class SchoolRefresh(
    val schedule: UpdateResult,
    val notices: NoticeSnapshot?,
    val noticeError: String?,
    val calendar: AcademicCalendarSnapshot?,
    val calendarError: String?,
    val term: AcademicTerm?,
    val integrationErrors: List<String>
)

/** One read-only transaction independent of which destination is visible. */
internal suspend fun synchronizeSchool(platform: PlatformActions, requested: AcademicTerm?, expectedRepository: ScheduleRepository = platform.repository): SchoolRefresh {
    val school = platform.school ?: throw SchoolException("请连接教务系统")
    val repository = expectedRepository
    val noticeStore = platform.noticeStore
    val calendarStore = platform.academicCalendarStore
    val generation = platform.sessionGeneration?.value
    val context = currentCoroutineContext()
    return withContext(Dispatchers.IO) {
        school.withSession {
            fun checkOwner() {
                context.ensureActive()
                if (platform.repository !== repository || platform.sessionGeneration?.value != generation) throw CancellationException("Account changed")
                if (!platform.sessionReady) throw SchoolException("请重新登录后同步")
            }
            checkOwner()
            var schoolCurrentTerm: String? = null
            val result = repository.synchronizeSchool(beforeCommit = ::checkOwner) {
                school.downloadSchoolTimetable(requested).also { schoolCurrentTerm = it.currentTermCode; checkOwner() }
            }
            checkOwner()
            // The timetable endpoint already identifies the school's current
            // term. Deliver its saved snapshot before unrelated calendar/notices.
            val integrate = result is UpdateResult.Success && (requested == null || schoolCurrentTerm == result.snapshot.termCode)
            val integrationErrors = if (integrate) platform.onScheduleSynchronized(result.snapshot) else emptyList()
            var terms: AcademicCalendarTerms? = null
            var calendarError: String? = null
            if (calendarStore != null) try {
                terms = school.fetchAcademicCalendarTerms()
                checkOwner()
                calendarStore.saveTerms(terms, ::checkOwner)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { calendarError = (e as? SchoolException)?.message ?: "校历学期获取失败，请检查网络后重试" }
            val term = requested ?: (result as? UpdateResult.Success)?.snapshot?.let { termFor(it.termCode) }
                ?: terms?.let { catalog -> catalog.terms.firstOrNull { it.code == catalog.currentCode } }
            if (calendarStore != null && calendarError == null && term != null) {
                var saving = false
                try {
                    val calendar = school.fetchAcademicCalendar(term)
                    checkOwner()
                    saving = true
                    calendarStore.save(calendar, ::checkOwner)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    calendarError = when {
                        e is SchoolException -> e.message
                        saving -> "校历保存失败，请检查存储空间后重试"
                        else -> "校历获取失败，请检查网络或重新连接教务"
                    }
                }
            }
            val noticeError = try {
                if (noticeStore != null) {
                    val notices = school.fetchNotices()
                    checkOwner()
                    noticeStore.save(notices)
                }
                null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { "通知同步失败，保留上次结果；请检查网络或重新登录。" }
            checkOwner()
            SchoolRefresh(result, noticeStore?.snapshot(), noticeError, term?.let { calendarStore?.snapshot(it.code) }, calendarError, term, integrationErrors)
        }
    }
}

internal suspend fun persistTermSelection(platform: PlatformActions, term: AcademicTerm, owner: ScheduleRepository) {
    val selection = platform.termSelection
    val generation = platform.sessionGeneration?.value
    val context = currentCoroutineContext()
    fun checkOwner() {
        context.ensureActive()
        if (platform.repository !== owner || platform.termSelection !== selection || platform.sessionGeneration?.value != generation)
            throw CancellationException("Semester owner changed")
    }
    withContext(Dispatchers.IO) {
        checkOwner()
        selection?.select(term, ::checkOwner)
    }
    checkOwner()
}

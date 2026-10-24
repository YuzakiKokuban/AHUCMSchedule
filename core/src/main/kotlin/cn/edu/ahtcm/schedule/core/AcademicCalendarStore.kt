package cn.edu.ahtcm.schedule.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The owning AccountStorage supplies a private, account-specific directory. */
class AcademicCalendarStore(private val directory: Path) {
    private val file = directory.resolve("academic-calendar.json")
    private val json = Json { ignoreUnknownKeys = true }
    var loadError: String? = null
        private set
    @Volatile private var catalog = try {
        if (Files.exists(file)) {
            require(Files.size(file) <= MAX_BYTES)
            json.decodeFromString<Catalog>(Files.newInputStream(file).bufferedReader(Charsets.UTF_8).use { it.readText() }).also(::validate)
        } else Catalog()
    } catch (_: Exception) {
        loadError = "本地校历读取失败，请重新同步"
        Catalog()
    }

    fun snapshot(termCode: String): AcademicCalendarSnapshot? = catalog.calendars[termCode]
    fun snapshots(): List<AcademicCalendarSnapshot> = catalog.calendars.values.sortedByDescending { it.term.code }
    fun availableTerms(): AcademicCalendarTerms? = catalog.terms

    @Synchronized fun save(snapshot: AcademicCalendarSnapshot, beforeCommit: () -> Unit = {}) {
        validateAcademicCalendar(snapshot)
        catalog.calendars[snapshot.term.code]?.let { previous ->
            // The endpoint has no declared total/date bounds. A reduced range cannot
            // be distinguished from a truncated response, so keep the known coverage.
            if (snapshot.days.first().date > previous.days.first().date || snapshot.days.last().date < previous.days.last().date) {
                throw SchoolException("学校返回的校历日期范围缩短，请重试或打开教务系统核对")
            }
        }
        persist(catalog.copy(calendars = catalog.calendars + (snapshot.term.code to snapshot)), beforeCommit)
    }

    @Synchronized fun saveTerms(terms: AcademicCalendarTerms, beforeCommit: () -> Unit = {}) {
        persist(catalog.copy(terms = terms), beforeCommit)
    }

    private fun validate(value: Catalog) {
        require(value.schema == 1 && value.calendars.size <= 100)
        value.terms?.let {
            require(it.terms.isNotEmpty() && it.terms.size <= 100)
            require(it.terms.distinctBy { term -> term.code }.size == it.terms.size)
            require(it.currentCode in it.terms.map { term -> term.code })
        }
        value.calendars.forEach { (code, snapshot) ->
            require(code == snapshot.term.code)
            validateAcademicCalendar(snapshot)
        }
    }

    private fun persist(next: Catalog, beforeCommit: () -> Unit) {
        beforeCommit()
        validate(next)
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "校历缓存过大" }
        Files.createDirectories(directory)
        val pending = Files.createTempFile(directory, ".academic-calendar-", ".tmp")
        try {
            FileChannel.open(pending, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeCommit()
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            catalog = next
            loadError = null
        } finally { Files.deleteIfExists(pending) }
    }

    @Serializable private data class Catalog(
        val schema: Int = 1,
        val terms: AcademicCalendarTerms? = null,
        val calendars: Map<String, AcademicCalendarSnapshot> = emptyMap()
    )
    private companion object { const val MAX_BYTES = 4L * 1024 * 1024 }
}

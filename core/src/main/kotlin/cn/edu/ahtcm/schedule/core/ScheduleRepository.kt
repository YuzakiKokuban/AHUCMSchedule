package cn.edu.ahtcm.schedule.core

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ScheduleRepository(private val directory: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = directory.resolve("timetable.json")
    var loadError: String? = null
        private set
    @Volatile private var catalog: Catalog = try {
        if (Files.exists(cache)) {
            require(Files.size(cache) <= 20L * 1024 * 1024)
            json.decodeFromString<Catalog>(Files.newInputStream(cache).bufferedReader(Charsets.UTF_8).use { it.readText() }).also { require(it.schema == 1) }
        } else Catalog()
    } catch (_: Exception) { loadError = "本地课表读取失败，请重新导入"; Catalog() }

    fun snapshot(): ScheduleSnapshot? {
        val current = catalog
        return current.terms[current.activeTerm]
    }
    fun snapshot(termCode: String): ScheduleSnapshot? = catalog.terms[termCode]
    fun snapshots(): List<ScheduleSnapshot> = catalog.terms.values.sortedByDescending { it.termCode }
    @Synchronized fun importXls(bytes: ByteArray): UpdateResult = update(bytes, "file")

    @Synchronized fun synchronize(fetch: () -> ByteArray): UpdateResult = try {
        update(fetch(), "school")
    } catch (_: Exception) {
        UpdateResult.Failure("无法获取最新课表，请检查网络或重新登录教务系统")
    }

    @Synchronized fun synchronizeSchool(beforeCommit: () -> Unit = {}, fetch: () -> SchoolTimetable): UpdateResult = try {
        beforeCommit()
        val downloaded = fetch()
        validatePeriods(downloaded.periods)
        update(downloaded.xls, "school", downloaded.periods, beforeCommit)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        UpdateResult.Failure("课表或作息时间获取失败，已保留上次课表")
    }

    private fun update(bytes: ByteArray, source: String, periods: List<ClassPeriod> = emptyList(), beforeCommit: () -> Unit = {}): UpdateResult {
        val snapshot = try {
            val parsed = XlsScheduleImporter().parse(bytes)
            val retainedPeriods = if (source == "file") catalog.terms[parsed.termCode]?.periods.orEmpty() else periods
            parsed.copy(source = source, periods = retainedPeriods)
        }
        catch (e: ImportException) { return UpdateResult.Failure(e.message ?: "课表校验失败") }
        val next = catalog.copy(activeTerm = snapshot.termCode, terms = catalog.terms + (snapshot.termCode to snapshot))
        var pending: Path? = null
        try {
            Files.createDirectories(directory)
            pending = Files.createTempFile(directory, ".timetable-", ".tmp")
            FileChannel.open(pending, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeCommit()
            Files.move(pending, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            catalog = next
            loadError = null
            return UpdateResult.Success(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return UpdateResult.Failure("本地课表保存失败，未替换现有课表")
        } finally {
            pending?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    @Serializable private data class Catalog(
        val schema: Int = 1, val activeTerm: String = "",
        val terms: Map<String, ScheduleSnapshot> = emptyMap()
    )
}

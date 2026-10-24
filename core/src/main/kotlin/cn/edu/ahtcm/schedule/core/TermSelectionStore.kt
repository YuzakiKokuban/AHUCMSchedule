package cn.edu.ahtcm.schedule.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared timetable/calendar semester preference, scoped to the owning account. */
class TermSelectionStore(private val directory: Path) {
    private val file = directory.resolve("selected-term.json")
    private val json = Json { ignoreUnknownKeys = true }
    var loadError: String? = null
        private set
    @Volatile private var term: AcademicTerm? = try {
        if (Files.exists(file)) {
            require(Files.size(file) <= 4096)
            json.decodeFromString<Selection>(Files.newInputStream(file).bufferedReader(Charsets.UTF_8).use { it.readText() }).also { require(it.schema == 1) }.term
        } else null
    } catch (_: Exception) {
        loadError = "本地学期选择读取失败，请重新选择学期"
        null
    }

    fun selected(): AcademicTerm? = term

    @Synchronized fun select(term: AcademicTerm, beforeCommit: () -> Unit = {}) {
        beforeCommit()
        val bytes = json.encodeToString(Selection(term = term)).toByteArray(Charsets.UTF_8)
        Files.createDirectories(directory)
        val pending = Files.createTempFile(directory, ".selected-term-", ".tmp")
        try {
            FileChannel.open(pending, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeCommit()
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            this.term = term
            loadError = null
        } finally { Files.deleteIfExists(pending) }
    }

    @Serializable private data class Selection(val schema: Int = 1, val term: AcademicTerm)
}

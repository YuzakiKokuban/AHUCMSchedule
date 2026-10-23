package cn.edu.ahtcm.schedule.core

import java.nio.file.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class NoticeSnapshot(val records: List<RawNotice>, val retrievedAt: String, val schema: Int = 1)

class NoticeStore(private val directory: Path) {
    private val file = directory.resolve("notices.json")
    private val json = Json { ignoreUnknownKeys = true }
    var loadError: String? = null
        private set
    @Volatile private var current: NoticeSnapshot? = try {
        if (Files.exists(file)) {
            require(Files.size(file) <= 4 * 1024 * 1024)
            json.decodeFromString<NoticeSnapshot>(Files.newInputStream(file).bufferedReader(Charsets.UTF_8).use { it.readText() }).also { require(it.schema == 1) }
        } else null
    } catch (_: Exception) { loadError = "本地通知读取失败，请重新同步"; null }

    fun snapshot() = current
    @Synchronized fun save(records: List<RawNotice>) {
        val next = NoticeSnapshot(records, Instant.now().toString())
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4 * 1024 * 1024) { "通知内容过大，已保留上次结果" }
        Files.createDirectories(directory)
        val pending = Files.createTempFile(directory, ".notices-", ".tmp")
        try {
            FileChannel.open(pending, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            current = next
            loadError = null
        } finally { Files.deleteIfExists(pending) }
    }
}

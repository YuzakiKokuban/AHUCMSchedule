package cn.edu.ahtcm.schedule.core

import java.nio.file.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Each account keeps its own cache. The selection marker contains only a digest. */
class AccountStorage(private val root: Path) {
    private val marker = root.resolve("active-account")
    @Volatile private var key = runCatching {
        if (Files.size(marker) != 64L) null else Files.newInputStream(marker).bufferedReader().use { it.readText() }.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()
    val activeAccountKey: String? get() = key
    var schedule = ScheduleRepository(directory(key))
        private set
    var notices = NoticeStore(directory(key))
        private set
    var academicCalendar = AcademicCalendarStore(directory(key))
        private set
    var termSelection = TermSelectionStore(directory(key))
        private set

    @Synchronized fun matchesSession(client: SchoolClient) = client.isAuthenticated && key != null && key == client.accountKey

    @Synchronized fun select(account: String) {
        require(account.isNotBlank())
        val next = accountKey(account)
        if (next == key) return
        val nextSchedule = ScheduleRepository(directory(next))
        val nextNotices = NoticeStore(directory(next))
        val nextCalendar = AcademicCalendarStore(directory(next))
        val nextTermSelection = TermSelectionStore(directory(next))
        Files.createDirectories(root)
        val pending = Files.createTempFile(root, ".account-", ".tmp")
        try {
            FileChannel.open(pending, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(next.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(pending, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            key = next
            schedule = nextSchedule
            notices = nextNotices
            academicCalendar = nextCalendar
            termSelection = nextTermSelection
        } finally { Files.deleteIfExists(pending) }
    }
    private fun directory(key: String?) = if (key == null) root else root.resolve("accounts").resolve(key)
}

internal fun accountKey(account: String) = MessageDigest.getInstance("SHA-256").digest(account.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

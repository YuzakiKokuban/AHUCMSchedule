package cn.edu.ahtcm.schedule.core

import java.nio.file.Files
import kotlin.test.*

class NoticeStoreTest {
    @Test fun `notice cache survives restart and preserves last success on a failed write`() {
        val dir = Files.createTempDirectory("notice-cache")
        val moved = dir.resolveSibling(dir.fileName.toString() + "-saved")
        try {
            val store = NoticeStore(dir)
            val raw = listOf(RawNotice("one", "调课通知", "2026-10-21 10:00:00"))
            store.save(raw)
            assertEquals(raw, NoticeStore(dir).snapshot()!!.records)
            Files.move(dir, moved)
            Files.writeString(dir, "block write")
            assertFails { store.save(emptyList()) }
            assertEquals(raw, store.snapshot()!!.records)
        } finally { dir.toFile().deleteRecursively(); moved.toFile().deleteRecursively() }
    }
}

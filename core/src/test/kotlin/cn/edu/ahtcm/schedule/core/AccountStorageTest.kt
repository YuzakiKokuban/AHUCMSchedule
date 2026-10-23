package cn.edu.ahtcm.schedule.core

import java.nio.file.Files
import kotlin.test.*

class AccountStorageTest {
    @Test fun `a completed login cannot sync until the same account storage is selected`() {
        val dir = Files.createTempDirectory("account-session")
        try {
            val storage = AccountStorage(dir)
            val client = SchoolClient(SchoolTransport { req ->
                val text = when {
                    java.net.URI(req.url).path == "/tpass/login" && req.method == "GET" -> "<form id='loginForm' action='/tpass/login'><input name='lt' value='demo'></form>"
                    req.url.contains("getMenu") -> "[\"new/student/xsgrkb/main.page\"]"
                    else -> "<title>教学管理系统</title>"
                }
                SchoolResponse(200, emptyMap(), text.toByteArray())
            })
            storage.select("account-a")
            client.login("account-b", "example-password")
            assertFalse(storage.matchesSession(client), "A login completed during cancelled UI must not write B data to A storage")
            storage.select("account-b")
            assertTrue(storage.matchesSession(client))
            storage.select("account-a")
            assertFalse(storage.matchesSession(client))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `switching account isolates caches and restart selects the last account`() {
        val dir = Files.createTempDirectory("account-storage")
        try {
            val storage = AccountStorage(dir)
            storage.select("anonymous-one")
            storage.notices.save(listOf(RawNotice("one", "调课一", "2026-10-21")))
            storage.select("anonymous-two")
            assertNull(storage.notices.snapshot())
            assertNull(storage.schedule.snapshot())
            storage.notices.save(listOf(RawNotice("two", "调课二", "2026-10-22")))
            assertEquals("two", AccountStorage(dir).notices.snapshot()!!.records.single().id)
            storage.select("anonymous-one")
            assertEquals("one", storage.notices.snapshot()!!.records.single().id)
            assertFalse(Files.newInputStream(dir.resolve("active-account")).bufferedReader().use { it.readText() }.contains("anonymous"))
        } finally { dir.toFile().deleteRecursively() }
    }
}

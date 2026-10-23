package cn.edu.ahtcm.schedule.core

import java.nio.file.Files
import kotlin.test.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class IntegrationTest {
    private fun snapshot(sections: List<Int> = listOf(1,2,5,6)) = ScheduleSnapshot("202601", "2026-2027-1", "2026-08-31", "2026-10-22T14:00:00Z", listOf(Lesson("护理,实验;课", "实验", "教师", "教室\nA101", "内容", "2026-10-19", 8, sections)), periods=(1..14).map { ClassPeriod(it, "%02d:00".format(it+6), "%02d:40".format(it+6)) })

    @Test fun `calendar splits discontinuous periods and uses actual Shanghai timestamps and stable IDs`() {
        val events = CalendarExport.events(snapshot(), "owner")
        assertEquals(2, events.size)
        assertEquals(java.time.ZonedDateTime.parse("2026-10-19T07:00:00+08:00[Asia/Shanghai]").toInstant().toEpochMilli(), events.first().startMillis)
        assertEquals(events.map { it.uid }, CalendarExport.events(snapshot().copy(retrievedAt="2026-10-23T14:00:00Z"), "owner").map { it.uid })
        assertNotEquals(events.first().uid, CalendarExport.events(snapshot(), "other").first().uid)
        val ics = CalendarExport.ics(snapshot(), "owner")
        assertTrue(ics.contains("SUMMARY:护理\\,实验\\;课\r\n"))
        assertTrue(ics.contains("LOCATION:教室\\nA101\r\n"))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertFails { CalendarExport.events(snapshot().copy(periods=emptyList()), "owner") }
    }

    @Test fun `parallel class details are retained in the calendar occurrence`() {
        val original = snapshot(listOf(1,2))
        val parallel = original.copy(lessons = original.lessons + original.lessons.first().copy(location="另一教室",content="另一授课内容"))
        val events = CalendarExport.events(parallel,"owner")
        assertEquals(1,events.size)
        assertTrue(events.single().location.contains("另一教室"))
        assertTrue(events.single().description.contains("另一授课内容"))
        assertTrue(events.single().description.contains("内容"))
    }

    @Test fun `encrypted login survives restart without plaintext and rejects corruption`() {
        val dir = Files.createTempDirectory("ahu-vault")
        val protector = object : CredentialProtector {
            val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
            override fun encrypt(clear: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let { it.init(Cipher.ENCRYPT_MODE,key); it.iv + it.doFinal(clear) }
            override fun decrypt(sealed: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").let { it.init(Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,sealed.copyOfRange(0,12))); it.doFinal(sealed.copyOfRange(12,sealed.size)) }
        }
        try {
            val path = dir.resolve("login.bin")
            val store = EncryptedCredentialStore(path, protector)
            val login = SavedLogin("anonymous-student", "secret-password")
            store.save(login)
            assertFalse(Files.readAllBytes(path).toString(Charsets.UTF_8).contains("secret-password"))
            assertEquals(login, EncryptedCredentialStore(path,protector).load())
            Files.write(path, byteArrayOf(0,1,2))
            assertFails { store.load() }
            store.clear(); assertNull(store.load())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `module payload groups exact weeks and revision excludes retrieval time`() {
        val first = XiaoAiExport.payload(snapshot(), "a".repeat(64))
        val next = XiaoAiExport.payload(snapshot().copy(retrievedAt="2026-10-23T14:00:00Z"), "a".repeat(64))
        assertEquals(first.revision, next.revision)
        assertEquals(listOf(8), first.courses.single().weeks)
        assertEquals(1,first.courses.single().day)
        assertEquals("com.yuzakikokuban.ahucm",first.source)
        assertEquals(14,first.timer.sections.size)
        assertNotEquals(first.revision,XiaoAiExport.payload(snapshot().copy(lessons=snapshot().lessons.map { it.copy(location="新教室") }),"a".repeat(64)).revision)
    }
}

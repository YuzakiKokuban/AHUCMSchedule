package com.yuzakikokuban.ahucm.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReleaseUpdateTest {
    private val digest = "a".repeat(64)
    private fun release(version: String = "0.4.1", url: String = "https://github.com/YuzakiKokuban/AHUCMSchedule/releases/download/v$version/QihuangSchedule-Android-$version.apk", hash: String = "sha256:$digest") = """
        {"tag_name":"v$version","draft":false,"prerelease":false,"body":"更新说明",
        "assets":[{"name":"QihuangSchedule-Android-$version.apk","state":"uploaded","size":3,"digest":"$hash","browser_download_url":"$url"}]}
    """.trimIndent()

    @Test fun `numeric version ordering rejects same and older releases`() {
        assertNull(ReleaseUpdate.parse(release(), "0.4.1"))
        assertNull(ReleaseUpdate.parse(release(), "0.5.0"))
        assertEquals("0.4.10", ReleaseUpdate.parse(release("0.4.10"), "0.4.9")!!.version)
        assertNull(ReleaseUpdate.parse(release("0.4.1-beta"), "0.4.0"))
    }
    @Test fun `candidate releases rank below their finished release`() {
        assertEquals("0.6.0", ReleaseUpdate.parse(release("0.6.0"), "0.6.0-rc.1")!!.version)
        assertEquals("0.6.0", ReleaseUpdate.parse(release("0.6.0"), "0.6.0-beta.9")!!.version)
        assertNull(ReleaseUpdate.parse(release("0.6.0-rc.1"), "0.6.0"))
        assertNull(ReleaseUpdate.parse(release("0.6.0-rc.1"), "0.6.0-rc.1"))
        assertEquals("0.6.0-rc.2", ReleaseUpdate.parse(release("0.6.0-rc.2"), "0.6.0-rc.1")!!.version)
        assertEquals("0.6.0-rc.1", ReleaseUpdate.parse(release("0.6.0-rc.1"), "0.6.0-beta.9")!!.version)
        assertNull(ReleaseUpdate.parse(release("0.6.0-beta.9"), "0.6.0-rc.1"))
    }
    @Test fun `official release exposes download digest and notes`() {
        val result = ReleaseUpdate.parse(release(), "0.4.0")!!
        assertEquals(digest, result.sha256)
        assertEquals(3L, result.size)
        assertEquals("更新说明", result.notes)
    }
    @Test fun `draft prerelease wrong source and missing digest fail closed`() {
        for (json in listOf(
            release().replace("\"draft\":false", "\"draft\":true"),
            release().replace("\"prerelease\":false", "\"prerelease\":true"),
            release(url = "https://evil.example/app.apk"),
            release(url = "http://github.com/YuzakiKokuban/AHUCMSchedule/releases/download/v0.4.1/QihuangSchedule-Android-0.4.1.apk"),
            release(hash = ""), release().replace("\"size\":3", "\"size\":0"),
            release().replace("\"size\":3", "\"size\":999999999"),
            release().replace("QihuangSchedule-Android-0.4.1.apk", "unofficial.apk"),
        )) {
            assertTrue(runCatching { ReleaseUpdate.parse(json, "0.4.0") }.isFailure)
        }
    }
    @Test fun `truncated excessive and corrupt downloads are rejected`() {
        val expected = ReleaseUpdate("0.4.1", "notes", "url", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val output = ByteArrayOutputStream()
        expected.copyVerified(ByteArrayInputStream("abc".toByteArray()), output) { }
        assertEquals("abc", output.toString("UTF-8"))
        for (input in listOf("ab", "abcd", "xyz")) {
            assertTrue(runCatching { expected.copyVerified(ByteArrayInputStream(input.toByteArray()), ByteArrayOutputStream()) { } }.isFailure)
        }
    }
}

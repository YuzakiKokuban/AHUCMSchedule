package com.yuzakikokuban.ahucm

import android.content.Context
import android.util.AtomicFile
import cn.edu.ahtcm.schedule.core.SchoolWebSession
import java.io.File
import kotlinx.serialization.json.Json

class AndroidBrowserSessionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "schedule/browser-session.bin"))
    private val protector = AndroidCredentialProtector()

    fun save(session: SchoolWebSession) = synchronized(LOCK) {
        require(session.casAuthenticated)
        require(session.account.matches(Regex("[0-9]{8,20}")))
        require(session.cookies.keys.all { it in ORIGINS })
        val clear = Json.encodeToString(session).toByteArray(Charsets.UTF_8)
        val encrypted = try {
            require(clear.size <= LIMIT - 28) { "学校登录信息过大，请重新登录" }
            protector.encrypt(clear)
        } finally { clear.fill(0) }
        require(encrypted.size <= LIMIT)
        check(file.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    fun load(): SchoolWebSession? = synchronized(LOCK) {
        val encrypted = try {
            file.openRead().use { input ->
                val bytes = ByteArray(LIMIT + 1)
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count == -1) break
                    length += count
                }
                require(length in 28..LIMIT) { "学校登录信息已损坏，请重新登录" }
                bytes.copyOf(length)
            }
        } catch (_: java.io.FileNotFoundException) { return@synchronized null }
        val clear = protector.decrypt(encrypted)
        try {
            Json.decodeFromString<SchoolWebSession>(clear.toString(Charsets.UTF_8)).also {
                require(it.casAuthenticated)
                require(it.account.matches(Regex("[0-9]{8,20}")))
                require(it.cookies.keys.all { key -> key in ORIGINS })
            }
        } finally { clear.fill(0) }
    }

    fun clear() = synchronized(LOCK) { file.delete() }

    private companion object {
        const val LIMIT = 65536
        val ORIGINS = setOf("https://jwxt.ahtcm.edu.cn", "https://tpass.ahtcm.edu.cn")
        val LOCK = Any()
    }
}

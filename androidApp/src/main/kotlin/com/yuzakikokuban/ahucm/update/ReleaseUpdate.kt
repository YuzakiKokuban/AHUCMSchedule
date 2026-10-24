package com.yuzakikokuban.ahucm.update

import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class ReleaseUpdate(val version: String, val notes: String, val url: String, val size: Long, val sha256: String) {
    fun copyVerified(input: InputStream, output: OutputStream, progress: (Int) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        val started = System.nanoTime()
        while (true) {
            check((System.nanoTime() - started) / 1_000_000_000 < 600) { "下载超时，请重试" }
            val count = input.read(buffer)
            if (count < 0) break
            received += count
            check(received <= size) { "安装包大小与发布信息不符" }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            progress((received * 100 / size).toInt())
        }
        check(received == size && digest.digest().joinToString("") { "%02x".format(it) } == sha256) {
            "安装包校验失败，请重新下载"
        }
    }

    companion object {
        const val PROJECT = "https://github.com/YuzakiKokuban/AHUCMSchedule"
        const val API = "https://api.github.com/repos/YuzakiKokuban/AHUCMSchedule/releases/latest"
        const val MAX_APK_SIZE = 150L * 1024 * 1024
        private fun versionParts(value: String): List<Int>? =
            if (Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(value)) value.split('.').map { it.toInt() } else null

        fun parse(json: String, currentVersion: String): ReleaseUpdate? {
            val root = Json.parseToJsonElement(json).jsonObject
            require(root["draft"]?.jsonPrimitive?.boolean == false && root["prerelease"]?.jsonPrimitive?.boolean == false) { "不是正式发布版本" }
            val tag = root.getValue("tag_name").jsonPrimitive.content
            val version = tag.removePrefix("v")
            val candidate = versionParts(version) ?: return null
            val current = requireNotNull(versionParts(currentVersion)) { "当前版本格式无效" }
            val difference = candidate.zip(current).firstOrNull { it.first != it.second } ?: return null
            if (difference.first < difference.second) return null
            val names = setOf("QihuangSchedule-Android-$version.apk", "AHUCMSchedule-Android-$version.apk")
            val asset = root.getValue("assets").jsonArray.map { it.jsonObject }.singleOrNull {
                it["name"]?.jsonPrimitive?.content in names && it["state"]?.jsonPrimitive?.content == "uploaded"
            } ?: error("正式发布尚无唯一 Android 安装包，请稍后重试")
            val name = asset.getValue("name").jsonPrimitive.content
            val url = asset.getValue("browser_download_url").jsonPrimitive.content
            require(url == "$PROJECT/releases/download/$tag/$name") { "安装包不是官方发布地址" }
            val size = asset.getValue("size").jsonPrimitive.long
            require(size in 1..MAX_APK_SIZE) { "安装包大小无效" }
            val digest = asset["digest"]?.jsonPrimitive?.content.orEmpty()
            require(Regex("sha256:[a-f0-9]{64}").matches(digest)) { "发布包缺少 SHA-256 校验，请稍后重试" }
            return ReleaseUpdate(version, root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(16000), url, size, digest.removePrefix("sha256:"))
        }
    }
}

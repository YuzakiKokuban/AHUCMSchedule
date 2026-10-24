package com.yuzakikokuban.ahucm.update

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import com.yuzakikokuban.ahucm.BuildConfig

data class UpdateState(
    val busy: Boolean = false,
    val message: String = "点击检查更新，获取 GitHub 最新正式版本",
    val release: ReleaseUpdate? = null,
    val progress: Int? = null,
    val ready: Boolean = false,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = MutableStateFlow(UpdateState())
    val apk: File get() = File(app.cacheDir, "updates/update.apk")

    fun checkUpdate() {
        if (state.value.busy) return
        state.value = UpdateState(busy = true, message = "正在检查更新…")
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    request(ReleaseUpdate.API) { connection ->
                        val bytes = connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            val started = System.nanoTime()
                            while (true) {
                                ensureActive()
                                check((System.nanoTime() - started) / 1_000_000_000 < 60) { "检查更新超时" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(output.size() + count <= 1024 * 1024) { "发布信息过大" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        ReleaseUpdate.parse(bytes.toString(Charsets.UTF_8), BuildConfig.VERSION_NAME)
                    }
                }
                state.value = UpdateState(release = release, message = if (release == null) "当前已是最新版本 ${BuildConfig.VERSION_NAME}" else "发现新版本 ${release.version}")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.value = UpdateState(message = "检查失败，请检查网络后重试；GitHub 访问可能受限") }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, ready = false, progress = 0, message = "正在下载更新…")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(apk.parentFile!!.isDirectory || apk.parentFile!!.mkdirs())
                    val partial = File.createTempFile("download-", ".part", apk.parentFile)
                    try {
                        request(release.url) { connection ->
                            val length = connection.contentLengthLong
                            check(length == -1L || length == release.size) { "安装包大小不符" }
                            connection.inputStream.use { input -> partial.outputStream().use { output ->
                                release.copyVerified(input, output) { percent ->
                                    ensureActive()
                                    state.value = state.value.copy(progress = percent)
                                }
                            } }
                        }
                        validateIdentity(partial, release)
                        ensureActive()
                        check(partial.renameTo(apk)) { "无法保存安装包" }
                    } finally { partial.delete() }
                }
                state.value = state.value.copy(busy = false, ready = true, progress = null, message = "下载完成，SHA-256 与签名校验通过")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.value = state.value.copy(busy = false, ready = false, progress = null, message = "下载或校验失败，请检查网络；调试签名版本无法覆盖升级到正式版") }
        }
    }

    suspend fun verifiedApk(): File = withContext(Dispatchers.IO) {
        val release = checkNotNull(state.value.release)
        check(state.value.ready && apk.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(apk.length() == release.size && digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
        validateIdentity(apk, release)
        apk
    }

    fun installationMessage(message: String) { state.value = state.value.copy(message = message) }
    fun installationFailed() { state.value = state.value.copy(ready = false, message = "安装包无法使用，请重新下载后重试") }

    @Suppress("DEPRECATION")
    private fun validateIdentity(file: File, release: ReleaseUpdate) {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(app.packageName, flags)
        val archive = requireNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags))
        check(archive.packageName == app.packageName && archive.versionName == release.version)
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(archiveCode > installedCode) { "不允许安装相同或较低版本" }
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val expected = certificates(installed)
        check(expected.isNotEmpty() && certificates(archive) == expected) { "安装包签名与当前应用不同" }
    }

    private fun <T> request(address: String, read: (HttpURLConnection) -> T): T {
        var uri = URI(address)
        repeat(6) {
            check(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443))
            check(uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "下载重定向不受信任" }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "QihuangSchedule/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", if (address == ReleaseUpdate.API) "application/vnd.github+json" else "application/octet-stream")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(checkNotNull(connection.getHeaderField("Location")))
                } else {
                    check(code == 200) { "服务器返回 $code" }
                    return read(connection)
                }
            } finally { connection.disconnect() }
        }
        error("重定向次数过多")
    }

    override fun onCleared() { scope.cancel(); super.onCleared() }
}

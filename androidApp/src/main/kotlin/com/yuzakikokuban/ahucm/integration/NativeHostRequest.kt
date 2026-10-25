package com.yuzakikokuban.ahucm.integration

import android.net.Uri
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Runs only inside XiaoAi. Its authorization is never returned over Binder or to JS. */
internal class NativeHostRequest(loader: ClassLoader) {
    @Volatile private var canceled = false
    @Volatile private var activeConnection: HttpsURLConnection? = null
    private val authorization = Class.forName("r31.b", true, loader)
        .getMethod("getAuthorization", Boolean::class.javaPrimitiveType)

    fun execute(raw: String): String {
        check(!canceled) { "background_job_finished" }
        val request = JSONObject(raw)
        val method = request.getString("method")
        val path = request.getString("url")
        require(method in listOf("GET", "POST", "PUT"))
        require(Regex("/course-multi/(tables|table|courseInfos|table_switch)").matches(path))
        val data = JSONObject(request.optJSONObject("data")?.toString() ?: "{}")
            .put("sourceName", "course-app-miui")
        val uri = Uri.parse("https://i.ai.mi.com" + path.replace("/course-multi/", "/course-multi-auth/")).buildUpon()
        if (method == "GET") data.keys().forEach { key -> uri.appendQueryParameter(key, data.get(key).toString()) }
        val auth = authorization.invoke(null, false) as? String
        check(!auth.isNullOrBlank()) { "host_authorization_unavailable" }
        check(!canceled) { "background_job_finished" }
        val connection = URL(uri.build().toString()).openConnection() as HttpsURLConnection
        activeConnection = connection
        try {
            check(!canceled) { "background_job_finished" }
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", auth)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("RequestId", UUID.randomUUID().toString().replace("-", ""))
            if (method != "GET") {
                connection.doOutput = true
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "host_http_rejected" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count: Int
                while (input.read(buffer).also { count = it } >= 0) {
                    check(output.size() + count <= 2_000_000) { "host_response_too_large" }
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString(Charsets.UTF_8.name())).toString()
            }
        } finally { connection.disconnect(); activeConnection = null }
    }
    fun cancel() { canceled = true; activeConnection?.disconnect() }
}

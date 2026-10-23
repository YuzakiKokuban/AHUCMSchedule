package cn.edu.ahtcm.schedule.core

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

internal fun validateBrowserCookies(cookies: Map<String,String>) {
    require(cookies.isNotEmpty() && cookies.keys.all { it in setOf("https://jwxt.ahtcm.edu.cn","https://tpass.ahtcm.edu.cn") })
    require(!cookies["https://jwxt.ahtcm.edu.cn"].isNullOrBlank())
    require(cookies.values.all { it.length <= 16384 && !it.contains('\r') && !it.contains('\n') })
}

data class SchoolRequest(val url: String, val method: String = "GET", val body: String? = null, val referer: String? = null)
data class SchoolResponse(val status: Int, val headers: Map<String, List<String>>, val bytes: ByteArray) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    fun text() = bytes.toString(Charsets.UTF_8)
}
fun interface SchoolTransport {
    fun exchange(request: SchoolRequest): SchoolResponse
    fun clearSession() = Unit
    fun installSessionCookies(cookies: Map<String, String>) { throw UnsupportedOperationException("Browser sessions unavailable") }
}
class SchoolException(message: String) : Exception(message)

/** Cookies stay in memory. No global cookie handler, credentials, or body logging. */
class SchoolHttpTransport : SchoolTransport {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    override fun clearSession() { cookies.cookieStore.removeAll() }
    override fun installSessionCookies(cookies: Map<String, String>) {
        validateBrowserCookies(cookies)
        this.cookies.cookieStore.removeAll()
        cookies.forEach { (origin, header) ->
            val uri = URI(origin)
            header.split(';').filter { it.isNotBlank() }.forEach { entry ->
                val split = entry.indexOf('=')
                require(split > 0)
                val cookie = java.net.HttpCookie(entry.substring(0,split).trim(), entry.substring(split+1).trim()).apply {
                    domain = uri.host; path = "/"; secure = true; isHttpOnly = true; version = 0
                }
                this.cookies.cookieStore.add(uri,cookie)
            }
        }
    }
    override fun exchange(request: SchoolRequest): SchoolResponse {
        val uri = URI(request.url)
        requireSchoolUrl(uri)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.requestMethod = request.method
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "AHUCMSchedule/0.1")
            connection.setRequestProperty("Cache-Control", "no-cache")
            request.referer?.let { connection.setRequestProperty("Referer", it) }
            cookies.get(uri, emptyMap()).forEach { (key, value) -> connection.setRequestProperty(key, value.joinToString("; ")) }
            if (request.method == "POST") {
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.orEmpty().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }
            cookies.put(uri, headers)
            if (connection.contentLengthLong > MAX_RESPONSE) throw SchoolException("学校返回文件过大")
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = input?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    if (out.size() + size > MAX_RESPONSE) throw SchoolException("学校返回文件过大")
                    out.write(buffer, 0, size)
                }
                out.toByteArray()
            } ?: byteArrayOf()
            return SchoolResponse(status, headers, bytes)
        } finally { connection.disconnect() }
    }
    companion object { private const val MAX_RESPONSE = 20 * 1024 * 1024 }
}

private fun requireSchoolUrl(uri: URI) {
    if (uri.scheme != "https" || uri.host !in setOf("tpass.ahtcm.edu.cn", "jwxt.ahtcm.edu.cn") || uri.userInfo != null || uri.port !in setOf(-1, 443))
        throw SchoolException("学校认证地址异常，已停止连接")
}

class SchoolClient(private val transport: SchoolTransport = SchoolHttpTransport()) {
    /** Serialize an entire login/cache or timetable/notice transaction, including across UI recreation. */
    @Synchronized fun <T> withSession(action: () -> T): T = action()
    @Volatile var isAuthenticated = false
        private set
    @Volatile var accountKey: String? = null
        private set
    private val school = "https://jwxt.ahtcm.edu.cn"
    private val welcome = "$school/new/welcome.page?ui=new"
    private val loginUrl = "https://tpass.ahtcm.edu.cn/tpass/login?service=https%3A%2F%2Fjwxt.ahtcm.edu.cn%2Fnew%2FssoLogin"

    @Synchronized fun login(account: String, password: String) {
        isAuthenticated = false
        accountKey = null
        transport.clearSession()
        if (account.isBlank() || password.isEmpty()) throw SchoolException("请输入学号和密码")
        val login = request(SchoolRequest(loginUrl))
        val doc = Jsoup.parse(login.text(), loginUrl)
        val form = doc.selectFirst("form#loginForm") ?: throw SchoolException("学校登录页面已变化，请稍后重试")
        val lt = form.selectFirst("input[name=lt]")?.attr("value").orEmpty()
        if (lt.isEmpty()) throw SchoolException("未获取到学校登录凭据")
        val params = form.select("input[type=hidden][name], input[name=lt], input[name=execution], input[name=_eventId]")
            .associate { it.attr("name") to it.attr("value") }.toMutableMap()
        params["rsa"] = SsoEncoder.encode(account + password + lt)
        params["ul"] = account.length.toString()
        params["pl"] = password.length.toString()
        val action = URI(loginUrl).resolve(form.attr("action")).toString()
        val result = request(SchoolRequest(action, "POST", encode(params), loginUrl))
        if (Jsoup.parse(result.text()).selectFirst("form#loginForm") != null)
            throw SchoolException("登录未成功，请检查学号、密码或在学校网页完成验证")
        // The school initializes allowed menu routes in this request. Without it,
        // authenticated requests silently redirect to the welcome page.
        val menus = request(SchoolRequest("$school/new/desktop/getMenu", "POST", "", welcome)).text()
        if (!menus.contains("xsgrkb/main.page")) throw SchoolException("未获取到课表访问权限，请重新登录")
        accountKey = accountKey(account)
        isAuthenticated = true
    }

    @Synchronized fun resumeSession(session: SchoolWebSession) {
        isAuthenticated = false
        accountKey = null
        require(session.casAuthenticated) { "A completed CAS login is required to bind this session" }
        val account = session.account
        val cookies = session.cookies
        require(account.isNotBlank() && account.length <= 100)
        validateBrowserCookies(cookies)
        transport.installSessionCookies(cookies)
        val menus = request(SchoolRequest("$school/new/desktop/getMenu", "POST", "", welcome)).text()
        if (!menus.contains("xsgrkb/main.page")) throw SchoolException("教务会话已失效，请在网页重新登录")
        val page = request(SchoolRequest("$school/new/student/xsgrkb/main.page",referer=welcome)).text()
        if (!page.contains("xsgrkb/week.page") || Jsoup.parse(page).selectFirst("form#loginForm") != null)
            throw SchoolException("教务会话未通过课表访问校验，请重新登录")
        accountKey = accountKey(account)
        isAuthenticated = true
    }

    @Synchronized fun logout() {
        isAuthenticated = false
        accountKey = null
        transport.clearSession()
    }

    @Synchronized fun downloadTimetable(): ByteArray = download(false).xls

    @Synchronized fun downloadSchoolTimetable(): SchoolTimetable = download(true)

    private fun download(requirePeriods: Boolean): SchoolTimetable {
        val main = request(SchoolRequest("$school/new/student/xsgrkb/main.page", referer = welcome)).text()
        val term = Regex("/new/student/xsgrkb/week\\.page\\?xnxqdm=(20\\d{4})").find(main)?.groupValues?.get(1)
            ?: throw SchoolException("登录已失效或学校课表页面已变化")
        val weekUrl = "$school/new/student/xsgrkb/week.page?xnxqdm=$term"
        val html = request(SchoolRequest(weekUrl, referer = welcome)).text()
        val periods = if (requirePeriods) SchoolPeriodParser.parse(html) else emptyList()
        val doc = Jsoup.parse(html)
        val selected = doc.selectFirst("select#xnxqdm option[selected]")
            ?: throw SchoolException("未获取到当前学期")
        if (selected.attr("value") != term) throw SchoolException("学校返回学期不一致，已保留原课表")
        val query = encode(linkedMapOf("xnxqdm" to term, "xnxqmc" to selected.text(), "zc" to "", "expname" to "download"))
        val bytes = request(SchoolRequest("$school/new/printkb/prtxskb?$query", referer = weekUrl)).bytes
        val parsed = try { XlsScheduleImporter().parse(bytes) }
            catch (_: ImportException) { throw SchoolException("学校未返回完整有效的课表，已保留上次结果") }
        if (parsed.termCode != term) throw SchoolException("导出课表学期不一致，已保留上次结果")
        return SchoolTimetable(bytes, periods)
    }

    @Synchronized fun fetchNotices(): List<RawNotice> {
        request(SchoolRequest("$school/new/notice/notice.page", referer = welcome))
        val notices = mutableListOf<RawNotice>()
        for (endpoint in listOf("getNotRead", "getRead")) {
            var page = 1
            var received = 0
            while (true) {
                val response = request(SchoolRequest("$school/new/notice/$endpoint", "POST", "page=$page&rows=2000", "$school/new/notice/notice.page"))
                val json = try { Json.parseToJsonElement(response.text()).jsonObject }
                    catch (_: Exception) { throw SchoolException("通知获取失败，请重新登录") }
                val rows = json["rows"]?.jsonArray ?: throw SchoolException("学校通知数据格式已变化")
                val total = json["total"]?.jsonPrimitive?.intOrNull ?: throw SchoolException("学校通知总数缺失")
                for (row in rows) {
                    val obj = row.jsonObject
                    fun field(key: String) = obj[key]?.jsonPrimitive?.contentOrNull ?: throw SchoolException("学校通知字段缺失")
                    notices += RawNotice(field("id"), Jsoup.parseBodyFragment(field("msg")).text(), field("cjsj"))
                }
                received += rows.size
                if (received >= total) break
                if (rows.isEmpty() || page >= 50) throw SchoolException("学校通知未完整返回，请稍后重试")
                page++
            }
        }
        return notices.distinctBy { it.id }
    }

    private fun request(initial: SchoolRequest): SchoolResponse {
        var current = initial
        repeat(10) {
            requireSchoolUrl(URI(current.url))
            val result = transport.exchange(current)
            if (result.status in listOf(301, 302, 303, 307, 308)) {
                val next = URI(current.url).resolve(result.header("Location") ?: throw SchoolException("学校跳转地址缺失"))
                requireSchoolUrl(next)
                current = if (result.status in listOf(307, 308)) current.copy(url = next.toString()) else SchoolRequest(next.toString(), referer = current.url)
            } else {
                if (result.status !in 200..299) throw SchoolException("学校服务暂不可用（${result.status}）")
                return result
            }
        }
        throw SchoolException("学校登录跳转次数过多，请稍后重试")
    }

    private fun encode(params: Map<String, String>) = params.entries.joinToString("&") { (k,v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
}

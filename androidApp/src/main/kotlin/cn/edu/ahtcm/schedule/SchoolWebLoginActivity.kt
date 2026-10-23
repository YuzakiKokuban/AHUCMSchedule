package com.yuzakikokuban.ahucm

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.ahtcm.schedule.core.SchoolClient
import cn.edu.ahtcm.schedule.core.SchoolWebSession
import java.util.UUID
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SchoolWebLoginActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { AndroidBrowserSessionStore(this) }
    private var browser: WebView? = null
    private val ports = mutableListOf<WebMessagePort>()
    private var channelToken: String? = null
    private var account: String? = null
    private var capturedAccount = false
    private var checking by mutableStateOf(false)
    private var message by mutableStateOf("请在学校页面完成登录和验证码")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme = remember { ThemeController(ColorSchemeMode.MonetSystem) }
            MiuixTheme(controller = theme) {
                Scaffold(topBar = { SmallTopAppBar(title = "教务登录") }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        TextButton(text = if (checking) "正在验证登录" else "完成登录", enabled = !checking,
                            onClick = { completeLogin() }, modifier = Modifier.padding(horizontal = 16.dp))
                        AndroidView(factory = { createBrowser().also { browser = it } },
                            modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !allowed(request.url)

            @Deprecated("Legacy WebView navigation callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !allowed(Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && !allowed(Uri.parse(url))) view.stopLoading()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                message = "学校页面安全连接失败，请稍后重试"
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) message = "教务系统暂时无法连接，请稍后重试"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val uri = url?.let(Uri::parse) ?: return
                if (!allowed(uri)) return
                if (uri.host == "tpass.ahtcm.edu.cn") attachAccountChannel(view)
                if (uri.host == "jwxt.ahtcm.edu.cn" &&
                    (uri.path == "/new/welcome.page" || uri.path.orEmpty().startsWith("/new/student"))) {
                    message = "学校页面已返回，请完成登录验证"
                    if (capturedAccount) completeLogin()
                }
            }
        }
        val view = this
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            if (!isDestroyed && !isFinishing) view.loadUrl("$SCHOOL/new/welcome.page?ui=new")
        }
    }

    private fun attachAccountChannel(view: WebView) {
        closePorts()
        val token = UUID.randomUUID().toString()
        channelToken = token
        val channel = view.createWebMessageChannel()
        ports += channel.toList()
        channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                if (channelToken != token) return
                val candidate = message.data?.trim().orEmpty()
                if (ACCOUNT.matches(candidate)) {
                    account = candidate
                    capturedAccount = true
                }
            }
        })
        // Only the main CAS frame receives the transferred port. Password fields are never inspected.
        val script = """
            (() => {
              const token = '$token';
              const connect = e => {
                if (e.data !== token || !e.ports || !e.ports[0]) return;
                window.removeEventListener('message', connect);
                const port = e.ports[0];
                const report = () => {
                  const field = document.querySelector('#un,input[name="username"],input[name="un"]');
                  if (field) port.postMessage(String(field.value || '').trim());
                };
                document.addEventListener('submit', report, true);
                const submit = HTMLFormElement.prototype.submit;
                HTMLFormElement.prototype.submit = function() { report(); return submit.apply(this, arguments); };
              };
              window.addEventListener('message', connect);
            })();
        """.trimIndent()
        view.evaluateJavascript(script) {
            if (!isDestroyed && channelToken == token && browser === view && view.url?.let { Uri.parse(it).host } == "tpass.ahtcm.edu.cn") {
                view.postWebMessage(WebMessage(token, arrayOf(channel[1])), Uri.parse(CAS))
            }
        }
    }

    private fun completeLogin() {
        if (checking || isFinishing || isDestroyed) return
        if (!capturedAccount) {
            message = "请在学校登录页完成登录，不能手工绑定会话账号"
            return
        }
        verifyAndSave()
    }

    private fun verifyAndSave() {
        val currentAccount = account?.takeIf { ACCOUNT.matches(it) } ?: return
        val manager = CookieManager.getInstance()
        val cookies = listOf(SCHOOL, CAS).associateWith { manager.getCookie(it).orEmpty() }
        if (cookies.getValue(SCHOOL).isBlank()) {
            message = "尚未取得学校登录状态，请先完成页面登录"
            return
        }
        checking = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val session = SchoolWebSession(currentAccount, cookies, casAuthenticated = true)
                    SchoolClient().resumeSession(session)
                    ensureActive()
                    store.save(session)
                    check(getSharedPreferences("auth-state",0).edit().putString("source","web").commit())
                }
                manager.flush()
                setResult(RESULT_OK)
                finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "登录验证未通过，请确认学校账号并在页面完成登录后重试" }
            finally { checking = false }
        }
    }

    private fun closePorts() {
        channelToken = null
        ports.forEach { runCatching { it.close() } }
        ports.clear()
    }

    override fun onDestroy() {
        scope.cancel()
        closePorts()
        browser?.apply { stopLoading(); webViewClient = WebViewClient(); destroy() }
        browser = null
        super.onDestroy()
    }

    private fun allowed(uri: Uri) = uri.scheme == "https" && uri.host in HOSTS &&
        (uri.port == -1 || uri.port == 443) && uri.userInfo == null

    private companion object {
        const val SCHOOL = "https://jwxt.ahtcm.edu.cn"
        const val CAS = "https://tpass.ahtcm.edu.cn"
        val HOSTS = setOf("jwxt.ahtcm.edu.cn", "tpass.ahtcm.edu.cn")
        val ACCOUNT = Regex("[0-9]{8,20}")
    }
}

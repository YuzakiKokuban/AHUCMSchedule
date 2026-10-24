package com.yuzakikokuban.ahucm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import cn.edu.ahtcm.schedule.core.*
import cn.edu.ahtcm.schedule.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong
import com.yuzakikokuban.ahucm.widget.TodayWidgetUpdates
import com.yuzakikokuban.ahucm.widget.TodayScheduleWidget

class MainActivity : ComponentActivity() {
    private val model by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[ImportViewModel::class.java]
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { model.read(it) }
    private val webLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) model.acceptWebLogin()
    }
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (model.calendar.hasPermissions()) model.enableCalendar()
        else model.report("未取得日历权限，尚未写入课程", true)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = object : PlatformActions {
            override val syncGate = model.syncGate
            override val currentTermOnlyIntegrations = true
            override val termSelection get() = model.storage.termSelection
            override val aboutAvailable = true
            override val widgetAvailable = true
            override fun addWidget() {
                // HyperOS/MIUI only show the widget confirmation after the app
                // holds the launcher's normal "install widget/shortcut"
                // permissions. They are granted at install time, but a user (or
                // an OEM cleaner) can still revoke them later, so check first
                // and point at the permission page instead of pinning silently.
                val required = listOf(
                    "com.miui.home.launcher.permission.INSTALL_WIDGET",
                    "com.android.launcher.permission.INSTALL_SHORTCUT",
                )
                val denied = required.filter { permission ->
                    val defined = runCatching { packageManager.getPermissionInfo(permission, 0) }.isSuccess
                    defined && checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (denied.isNotEmpty()) {
                    model.report("请先允许「桌面快捷方式」权限，再创建桌面小组件", true)
                    val opened = runCatching {
                        startActivity(android.content.Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName))
                    }.isSuccess
                    if (!opened) runCatching {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                    }
                    return
                }
                val manager = android.appwidget.AppWidgetManager.getInstance(this@MainActivity)
                val requested = manager.isRequestPinAppWidgetSupported &&
                    manager.requestPinAppWidget(android.content.ComponentName(this@MainActivity, TodayScheduleWidget::class.java), null, null)
                if (requested) model.report("已请求添加“今日课表”小组件，请在系统提示中确认")
                else model.report("请长按桌面，在小组件中添加岐黄课表的“今日课表”")
            }
            override fun openAbout() { startActivity(Intent(this@MainActivity, com.yuzakikokuban.ahucm.update.AboutActivity::class.java)) }
            override val webLoginAvailable = true
            override fun openWebLogin(account: String) {
                webLogin.launch(Intent(this@MainActivity,SchoolWebLoginActivity::class.java).putExtra("account",account))
            }
            override fun restoreWebSession() = model.restoreWebSession()
            override fun clearWebSession() = model.clearWebSession()
            override fun passwordLoginSelected() = model.passwordLoginSelected()
            override val sessionGeneration = model.sessionGeneration
            override fun connectionFailed(error: Throwable) {
                android.util.Log.w("AHUCMSchedule", "Connection failure type=${error.javaClass.name}; cause=${error.cause?.javaClass?.name}")
            }
            override val repository get() = model.storage.schedule
            override val school = model.school
            override val credentials = model.credentials
            override val sessionReady get() = model.storage.matchesSession(model.school)
            override val noticeStore get() = model.storage.notices
            override val academicCalendarStore get() = model.storage.academicCalendar
            override fun selectAccount(account: String) { model.selectAccount(account) }
            override val foreground = model.foreground
            override val selections = model.selections
            override val integrationFeedback = model.feedback
            override val calendarActionLabel = "同步到系统日历"
            override fun importCalendar(snapshot: ScheduleSnapshot) {
                if (model.calendar.hasPermissions()) model.enableCalendar()
                else calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))
            }
            override val calendarLinked get() = model.calendarLinked
            override val moduleLinked get() = model.moduleLinked
            override fun disableCalendar() { model.disable("calendar") }
            override fun disableXiaoAi() { model.disable("module") }
            override val moduleSupported = BuildConfig.APPLICATION_ID == "com.yuzakikokuban.ahucm"
            override val moduleActivation = (application as ScheduleApplication).moduleActivation
            override val moduleStatus = model.moduleStatus
            override fun manageXiaoAi(snapshot: ScheduleSnapshot) { model.enableModule() }
            override fun onScheduleSynchronized(snapshot: ScheduleSnapshot): List<String> {
                TodayWidgetUpdates.request(this@MainActivity)
                return model.syncIntegrations(snapshot)
            }
            override fun consumeFeedback(id: Long) { if (model.feedback.value?.id == id) model.feedback.value = null }
            override fun consumeSelection(token: Long) {
                if (model.selections.value?.token == token) model.selections.value = null
                TodayWidgetUpdates.request(this@MainActivity)
            }
            override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) {
                picker.launch(arrayOf("application/vnd.ms-excel", "application/octet-stream"))
            }
            override fun openSchool() {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://jwxt.ahtcm.edu.cn/new/welcome.page?ui=new")))
            }
        }
        setContent { App(actions) }
    }
    override fun onStart() { super.onStart(); model.foreground.value = true; TodayWidgetUpdates.request(this) }
    override fun onStop() { model.foreground.value = false; super.onStop() }
}

/** Turns the durable sync state into actionable text; the phase/message keep a
 *  stuck job from being shown as a fresh "waiting" state. */
internal fun moduleStatusText(state: String?, phase: String?, message: String?): String = when (state) {
    "applied" -> "小爱课表已更新并核验"
    "failed" -> when (message) {
        "course_write_not_persisted" -> "小爱未保存课程，请重新点击「开启小爱课表自动管理」"
        "server_readback_mismatch" -> "小爱读回内容不一致，请重新点击「开启小爱课表自动管理」"
        "managed_table_identity_changed" -> "小爱中已存在同名课表，请在小爱课程表中删除后重试"
        else -> "小爱更新失败，请检查小爱账号与模块状态"
    }
    // Pending jobs are repaired on the next host claim. Never recommend stopping
    // management here: cancellation of an unclaimed pending job cannot be resumed.
    "pending" -> when (phase) {
        "verifying" -> "小爱课表正在核验或修复；请保持小爱课程表打开，或重新点击「开启小爱课表自动管理」"
        else -> "等待小爱助手完成更新；请打开小爱课程表并保持前台"
    }
    else -> "等待小爱助手完成更新；请打开小爱课程表并保持前台"
}

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val root = application.filesDir.toPath().resolve("schedule")
    val storage = AccountStorage(root)
    private val passwordStore = EncryptedCredentialStore(root.resolve("login.bin"), AndroidCredentialProtector())
    val credentials: CredentialStore = object : CredentialStore {
        override fun load(): SavedLogin? = if (authPrefs.getString("source",null)=="web") null else passwordStore.load()
        override fun save(login: SavedLogin) = passwordStore.save(login)
        override fun clear() = passwordStore.clear()
    }
    val school = SchoolClient()
    val syncGate = SyncGate()
    val browserSessions = AndroidBrowserSessionStore(application)
    val sessionGeneration = MutableStateFlow(0L)
    val calendar = AndroidCalendarSync(application)
    val foreground = MutableStateFlow(false)
    val selections = MutableStateFlow<FileSelection?>(null)
    val feedback = MutableStateFlow<IntegrationFeedback?>(null)
    val moduleStatus = MutableStateFlow("需要支持 API 102 的 LSPosed；启用岐黄课表并勾选小爱助手")
    private val authPrefs = application.getSharedPreferences("auth-state",0)
    private val prefs = application.getSharedPreferences("integrations", 0)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequence = AtomicLong()
    private val syncUri = Uri.parse("content://com.yuzakikokuban.ahucm.schedule.sync")
    val calendarLinked get() = storage.activeAccountKey?.let { prefs.getBoolean("calendar:$it",false) } ?: false
    val moduleLinked get() = storage.activeAccountKey?.let { prefs.getBoolean("module:$it",false) } ?: false

    init {
        io.launch {
            while (isActive) {
                if (foreground.value) {
                    (context as ScheduleApplication).refreshModuleActivation()
                    if (moduleLinked && context.moduleActivation.value) runCatching { refreshModuleStatus() }
                }
                delay(2000)
            }
        }
    }
    fun passwordLoginSelected() {
        check(authPrefs.edit().putString("source","password").commit())
        browserSessions.clear()
    }
    fun selectAccount(account: String) = TodayWidgetUpdates.accountChanged(context) { storage.select(account) }
    fun restoreWebSession(): Boolean = school.withSession {
        if (authPrefs.getString("source",null)=="password") return@withSession false
        val session = browserSessions.load() ?: return@withSession false
        school.resumeSession(session)
        // Web login is the selected source; obsolete/corrupt password data must
        // neither block it nor become a fallback for another account.
        runCatching { passwordStore.clear() }
        selectAccount(session.account)
        true
    }
    fun acceptWebLogin() { io.launch {
        try {
            check(restoreWebSession())
            sessionGeneration.value += 1L
            report("已连接教务系统，正在同步最新课表")
        } catch(_:Exception) { report("网页登录会话未能连接，请重新打开网页登录",true) }
    } }
    fun clearWebSession() {
        browserSessions.clear()
        check(authPrefs.edit().clear().commit())
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.webkit.CookieManager.getInstance().removeAllCookies {
                android.webkit.CookieManager.getInstance().flush(); done.countDown()
            }
        }
        check(done.await(5,java.util.concurrent.TimeUnit.SECONDS)) { "cookie_clear_pending" }
    }
    fun report(message: String, error: Boolean = false) { feedback.value = IntegrationFeedback(sequence.incrementAndGet(),message,error) }
    fun disable(kind: String) {
        val owner = storage.activeAccountKey ?: return
        prefs.edit().putBoolean("$kind:$owner",false).apply()
        if (kind=="calendar") report("已停止自动更新日历，已有课程保留")
        else io.launch {
            try {
                val status = context.contentResolver.call(syncUri,"status",null,null)
                var queued = status
                var canceled = false
                for (attempt in 0..2) {
                    val queuedOwner = queued?.getString("owner").orEmpty()
                    val queuedTerm = queued?.getString("termCode").orEmpty()
                    if (queuedOwner.isNotEmpty()) prefs.edit().putBoolean("module:$queuedOwner",false).apply()
                    val receipt = context.contentResolver.call(syncUri,"cancel",null,Bundle().apply {
                        putString("owner",queuedOwner);putString("termCode",queuedTerm)
                    }) ?: error("missing_cancel_receipt")
                    if (receipt.getBoolean("cancelMatched")) { canceled=true;break }
                    queued=receipt
                }
                check(canceled) { "cancel_not_matched" }
                moduleStatus.value="已停止自动管理；已在途的单个请求可能仍会完成"
                report("已停止小爱自动管理；已有课表保留，在途请求只做核验")
            } catch(_:Exception) { report("自动管理已关闭，但队列取消未确认，请检查小爱状态",true) }
        }
    }
    private fun fresh(): ScheduleSnapshot = school.withSession {
        check(storage.matchesSession(school)) { "请先登录教务系统" }
        when(val result = storage.schedule.synchronizeSchool { school.downloadSchoolTimetable() }) {
            is UpdateResult.Success -> result.snapshot.also { TodayWidgetUpdates.request(context) }
            is UpdateResult.Failure -> error(result.message)
        }
    }
    fun enableCalendar() { io.launch {
        try {
            school.withSession {
                val snapshot = fresh()
                val owner = requireNotNull(storage.activeAccountKey)
                val count = calendar.sync(snapshot, owner)
                prefs.edit().putBoolean("calendar:$owner",true).apply()
                report("已导入 $count 次课程，之后随教务课表自动更新")
            }
        } catch (_: Exception) { report("日历同步未完成，请先登录并检查日历权限；已有课程保留",true) }
    } }
    fun enableModule() { io.launch {
        try {
            check((context as ScheduleApplication).moduleActivation.value) { "模块尚未激活" }
            school.withSession {
                val snapshot = fresh()
                val owner = requireNotNull(storage.activeAccountKey)
                submitModule(snapshot, owner, true)
                prefs.edit().putBoolean("module:$owner",true).apply()
                report(moduleStatus.value)
            }
        } catch (_: Exception) { report("小爱自动管理未开启，请先登录并在 LSPosed 启用岐黄课表",true) }
    } }
    fun syncIntegrations(snapshot: ScheduleSnapshot): List<String> {
        val owner = storage.activeAccountKey ?: return emptyList()
        val errors = mutableListOf<String>()
        if(calendarLinked) try { calendar.sync(snapshot,owner) } catch (_: Exception) { errors += "课表已更新，日历同步失败，请检查权限" }
        if(moduleLinked && (context as ScheduleApplication).moduleActivation.value) try { submitModule(snapshot,owner,false) } catch (_: Exception) { errors += "课表已更新，小爱更新尚未完成，请查看自动管理状态" }
        return errors
    }
    private fun submitModule(snapshot: ScheduleSnapshot, owner: String, force: Boolean) {
        val payload = XiaoAiExport.payload(snapshot,owner)
        val key = "module-revision:$owner:${snapshot.termCode}"
        if(!force && prefs.getString(key,null)==payload.revision) { refreshModuleStatus(); return }
        val reply = context.contentResolver.call(syncUri,"apply",null, Bundle().apply { putString("payload",XiaoAiExport.json(payload));putBoolean("resume",force) })
            ?: error("missing_module_receipt")
        check(reply.getInt("schema")==1 && reply.getString("owner")==owner && reply.getString("termCode")==snapshot.termCode && reply.getString("revision")==payload.revision)
        prefs.edit().putString(key,payload.revision).apply()
        showModuleState(reply)
        if(reply.getString("state")!="applied" && foreground.value) {
            io.launch(Dispatchers.Main) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("voiceassist://aiweb/?source=widget&flag=268468224&url=https%3A%2F%2Fi.ai.mi.com%2Fh5%2Fprecache%2Fai-schedule%2F")).setPackage("com.miui.voiceassist").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
    private fun refreshModuleStatus() = school.withSession {
        val snapshot = storage.schedule.snapshot() ?: return@withSession
        val owner = storage.activeAccountKey ?: return@withSession
        val expected = XiaoAiExport.payload(snapshot,owner).revision
        val reply = context.contentResolver.call(syncUri,"status",null,Bundle().apply { putString("owner",owner);putString("termCode",snapshot.termCode) }) ?: return@withSession
        if(reply.getString("owner")==owner && reply.getString("termCode")==snapshot.termCode && reply.getString("revision")==expected) showModuleState(reply)
        else moduleStatus.value="当前课表有新版本，等待更新到小爱"
    }
    private fun showModuleState(reply: Bundle) {
        moduleStatus.value = moduleStatusText(reply.getString("state"), reply.getString("phase"), reply.getString("message"))
    }
    fun read(uri: Uri?) {
        val token = sequence.incrementAndGet()
        if (uri == null) { selections.value = FileSelection(token, null); return }
        io.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        require(output.size() + count <= 20 * 1024 * 1024); output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("unreadable")
            }
            selections.value = result.fold({ FileSelection(token,it) }, { FileSelection(token,null,"无法读取文件，或文件超过 20 MB") })
        }
    }
    override fun onCleared() { io.cancel(); super.onCleared() }
}

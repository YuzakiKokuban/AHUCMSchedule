package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.ahtcm.schedule.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.window.WindowDialog

interface PlatformActions {
    val webLoginAvailable: Boolean get() = false
    fun openWebLogin(account: String) = Unit
    fun restoreWebSession(): Boolean = false
    fun clearWebSession() = Unit
    fun passwordLoginSelected() = Unit
    val sessionGeneration: StateFlow<Long>? get() = null
    fun connectionFailed(error: Throwable) = Unit
    val credentials: CredentialStore? get() = null
    val calendarActionLabel: String? get() = null
    fun importCalendar(snapshot: ScheduleSnapshot) = Unit
    val calendarLinked: Boolean get() = false
    val moduleLinked: Boolean get() = false
    fun disableCalendar() = Unit
    fun disableXiaoAi() = Unit
    val moduleSupported: Boolean get() = false
    val moduleStatus: StateFlow<String>? get() = null
    fun manageXiaoAi(snapshot: ScheduleSnapshot) = Unit
    fun onScheduleSynchronized(snapshot: ScheduleSnapshot): List<String> = emptyList()
    val integrationFeedback: StateFlow<IntegrationFeedback?>? get() = null
    fun consumeFeedback(id: Long) = Unit
    val repository: ScheduleRepository
    val school: SchoolClient? get() = null
    val sessionReady: Boolean get() = school?.isAuthenticated == true
    val noticeStore: NoticeStore? get() = null
    val foreground: StateFlow<Boolean>? get() = null
    fun selectAccount(account: String) = Unit
    fun chooseXls(onResult: (ByteArray?, String?) -> Unit)
    fun openSchool()
    val selections: StateFlow<FileSelection?>? get() = null
    fun consumeSelection(token: Long) = Unit
}

data class IntegrationFeedback(val id: Long, val message: String, val error: Boolean = false)

data class FileSelection(val token: Long, val bytes: ByteArray?, val error: String? = null)
private data class SchoolRefresh(val schedule: UpdateResult, val notices: NoticeSnapshot?, val noticeError: String?, val integrationErrors: List<String>)

private val Ink: Color @Composable get() = MiuixTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
private val Accent: Color @Composable get() = MiuixTheme.colorScheme.primary
private val Background: Color @Composable get() = MiuixTheme.colorScheme.background
private val Shanghai = ZoneId.of("Asia/Shanghai")

@Composable
fun App(platform: PlatformActions, today: LocalDate = LocalDate.now(Shanghai)) {
    var snapshot by remember { mutableStateOf(platform.repository.snapshot()) }
    var page by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var savedLogin by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(platform.repository.loadError) }
    var failure by remember { mutableStateOf(platform.repository.loadError != null) }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authenticated by remember { mutableStateOf(platform.sessionReady) }
    var noticeSnapshot by remember { mutableStateOf(platform.noticeStore?.snapshot()) }
    var noticeError by remember { mutableStateOf(platform.noticeStore?.loadError) }
    val foreground = platform.foreground?.collectAsState()?.value ?: true
    val currentForeground by rememberUpdatedState(foreground)
    var nextAutoSync by remember { mutableLongStateOf(0L) }
    var monday by remember(snapshot?.termCode) {
        mutableStateOf(today.minusDays((today.dayOfWeek.value - 1).toLong()))
    }
    val generation = platform.sessionGeneration?.collectAsState()?.value ?: 0L
    LaunchedEffect(generation) {
        if (platform.sessionReady) {
            authenticated = true
            nextAutoSync = 0L
            snapshot = platform.repository.snapshot()
            noticeSnapshot = platform.noticeStore?.snapshot()
        }
    }
    val scope = rememberCoroutineScope()
    suspend fun syncSchool() {
        val school = platform.school ?: return
        if (!platform.sessionReady) {
            authenticated = false
            message = "请重新登录后同步；当前账号尚未完成连接。"
            failure = true
            return
        }
        if (busy) return
        busy = true
        syncing = true
        try {
            val refreshed = withContext(Dispatchers.IO) {
                school.withSession {
                    if (!platform.sessionReady) throw SchoolException("请重新登录后同步")
                    val repository = platform.repository
                    val store = platform.noticeStore
                    val result = repository.synchronizeSchool { school.downloadSchoolTimetable() }
                    val error = try {
                        if (store != null) store.save(school.fetchNotices())
                        null
                    } catch (_: Exception) { "通知同步失败，保留上次结果；请检查网络或重新登录。" }
                    SchoolRefresh(result, store?.snapshot(), error, if (result is UpdateResult.Success) platform.onScheduleSynchronized(result.snapshot) else emptyList())
                }
            }
            when (val result = refreshed.schedule) {
                is UpdateResult.Success -> {
                    snapshot = result.snapshot
                    failure = false
                    message = refreshed.integrationErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")
                    failure = refreshed.integrationErrors.isNotEmpty()
                }
                is UpdateResult.Failure -> {
                    failure = true
                    message = result.message + "；当前显示上次保存的课表，请留意同步时间。"
                }
            }
            noticeSnapshot = refreshed.notices
            noticeError = refreshed.noticeError
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            failure = true
            message = "连接未完成，请重新登录后同步；上次保存的数据已保留。"
        } finally { busy = false; syncing = false }
    }
    LaunchedEffect(authenticated, foreground) {
        if (authenticated && foreground) {
            snapshot = platform.repository.snapshot()
            noticeSnapshot = platform.noticeStore?.snapshot()
            nextAutoSync = 0L
        }
    }
    LaunchedEffect(authenticated) {
        if (authenticated) {
            while (isActive) {
                if (currentForeground && !busy && System.currentTimeMillis() >= nextAutoSync) {
                    nextAutoSync = System.currentTimeMillis() + 5 * 60 * 1000L
                    syncSchool()
                }
                delay(1000L)
            }
        }
    }
    suspend fun authenticate(login: SavedLogin, persist: Boolean) {
        authenticated = false
        busy = true
        try {
            val saveError = withContext(Dispatchers.IO) {
                val school = platform.school ?: throw SchoolException("请连接教务系统")
                school.withSession {
                    school.login(login.account, login.password)
                    platform.selectAccount(login.account)
                    if (persist) runCatching { platform.passwordLoginSelected(); platform.credentials?.save(login) }.exceptionOrNull()
                    else null
                }
            }
            snapshot = platform.repository.snapshot()
            noticeSnapshot = platform.noticeStore?.snapshot()
            noticeError = platform.noticeStore?.loadError
            authenticated = platform.sessionReady
            if (saveError != null) {
                // A failed atomic save may have left another account's old login.
                // Clear that stale login without treating the successful login as failed.
                savedLogin = withContext(Dispatchers.IO) { runCatching { platform.credentials?.clear() }.isFailure }
                failure = true
                message = if (savedLogin) "本次已登录，但账号无法保存；请清除旧登录信息后重试" else "本次已登录，但账号未能保存，下次需重新登录"
            } else {
                savedLogin = platform.credentials != null
                failure = false
                message = null
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            failure = true
            platform.connectionFailed(e)
            message = (e as? SchoolException)?.message ?: "自动登录未完成，请检查网络或重新登录"
        } finally { busy = false }
    }
    LaunchedEffect(platform) {
        if (!platform.sessionReady && platform.webLoginAvailable) {
            busy = true
            try {
                val restored = withContext(Dispatchers.IO) { platform.restoreWebSession() }
                if (restored) {
                    authenticated = platform.sessionReady
                    savedLogin = true
                    snapshot = platform.repository.snapshot()
                    noticeSnapshot = platform.noticeStore?.snapshot()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "网页会话无法恢复，请重新网页登录"; failure = true }
            finally { busy = false }
        }
        if (platform.credentials != null) {
            busy = true
            try {
                val stored = withContext(Dispatchers.IO) { platform.credentials?.load() }
                if (stored != null) {
                    account = stored.account
                    savedLogin = true
                    if (!platform.sessionReady) authenticate(stored, false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "本地账号读取失败，请重新登录"; failure = true }
            finally { busy = false }
        }
        platform.integrationFeedback?.collect { feedback ->
            if (feedback != null) {
                snapshot = platform.repository.snapshot()
                message = feedback.message; failure = feedback.error
                platform.consumeFeedback(feedback.id)
            }
        }
    }
    fun loginSchool() {
        if (busy) return
        val login = SavedLogin(account.trim(), password)
        password = ""
        scope.launch { authenticate(login, true) }
    }
    suspend fun receiveFile(bytes: ByteArray?, error: String?) {
        try {
                if (error != null) { failure = true; message = error; return }
                if (bytes == null) return
                busy = true
                val result = withContext(Dispatchers.IO) { platform.repository.importXls(bytes) }
                when (result) {
                    is UpdateResult.Success -> {
                        snapshot = result.snapshot
                        failure = false
                        message = null
                    }
                    is UpdateResult.Failure -> {
                        failure = true
                        message = result.message + "；原有课表已保留。"
                    }
                }
        } finally { busy = false }
    }
    LaunchedEffect(platform) {
        platform.selections?.collect { selection ->
            if (selection != null) {
                receiveFile(selection.bytes, selection.error)
                platform.consumeSelection(selection.token)
            }
        }
    }
    fun importFile() {
        if (busy) return
        busy = true
        platform.chooseXls { bytes, error ->
            scope.launch { receiveFile(bytes, error) }
        }
    }
    val theme = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    MiuixTheme(controller = theme) {
        Scaffold(
            topBar = {
                SmallTopAppBar(title = "安中课表", titlePadding = 16.dp, actionIconPadding = 12.dp, actions = {
                    val spin = if (syncing) {
                        val transition = rememberInfiniteTransition()
                        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing))).value
                    } else 0f
                    IconButton(onClick = { if (authenticated) scope.launch { syncSchool() } else { page = 2; message = "请登录教务系统，之后打开应用会自动同步"; failure = false } },
                        enabled = !busy, modifier = Modifier.size(44.dp)) {
                        Icon(MiuixIcons.Refresh,
                            contentDescription = "同步",
                            modifier = Modifier.size(24.dp).rotate(spin), tint = if (syncing) Muted else Ink)
                    }
                })
            },
            bottomBar = {
                NavigationBar(showDivider = false) {
                    listOf(MiuixIcons.Months, MiuixIcons.Messages, MiuixIcons.Link).forEachIndexed { index, icon ->
                        NavigationBarItem(selected = page == index, onClick = { page = index }, icon = icon,
                            label = listOf("我的课表", "调课通知", "连接教务")[index])
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                if (message != null) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (failure) Color(0xFFFFE8E2) else Color(0xFFE2EEE5)).padding(14.dp)) {
                        Text(message!!, fontSize = 13.sp, color = if (failure) Color(0xFF9D3829) else Ink)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                AnimatedContent(targetState = page, modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { direction * it / 12 }) togetherWith
                            (fadeOut(tween(140)) + slideOutHorizontally(tween(240)) { -direction * it / 12 })
                    }, label = "page") { destination ->
                  Column(Modifier.fillMaxSize()) {
                   when (destination) {
                    0 -> {
                        val current = snapshot
                        if (current == null) {
                            EmptySchedule({ platform.openSchool() }, { importFile() })
                        } else {
                            val week = ChronoUnit.WEEKS.between(LocalDate.parse(current.weekOneMonday), monday) + 1
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(current.termLabel, fontSize = 12.sp, color = Muted)
                                    Text("${monday.monthValue}/${monday.dayOfMonth} — ${monday.plusDays(6).monthValue}/${monday.plusDays(6).dayOfMonth}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
                                    Text(if (week > 0 && monday.toString() <= current.calendarEndDate) "第 $week 周" else "所选日期不在导入的教学周内", color = Muted, fontSize = 12.sp)
                                }
                                IconButton(onClick = { monday = monday.minusWeeks(1) }, modifier = Modifier.size(40.dp)) {
                                    Icon(MiuixIcons.ChevronBackward, contentDescription = "上一周", modifier = Modifier.size(20.dp), tint = Ink)
                                }
                                TextButton("本周", { monday = today.minusDays((today.dayOfWeek.value - 1).toLong()) },
                                    minWidth = 48.dp, minHeight = 36.dp, insideMargin = PaddingValues(horizontal = 8.dp))
                                IconButton(onClick = { monday = monday.plusWeeks(1) }, modifier = Modifier.size(40.dp)) {
                                    Icon(MiuixIcons.ChevronForward, contentDescription = "下一周", modifier = Modifier.size(20.dp), tint = Ink)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            val formatted = runCatching {
                                Instant.parse(current.retrievedAt).atZone(Shanghai).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                            }.getOrDefault(current.retrievedAt)
                            Text(if (current.source == "school") "上次教务同步 · $formatted${if (!authenticated) "  ／  请登录检查更新" else ""}" else "文件导入 · $formatted  ／  请同步确认最新安排", fontSize = 12.sp, color = Muted)
                            if (current.periods.isEmpty()) Text("作息时间待同步", fontSize = 11.sp, color = Muted)
                            Spacer(Modifier.height(8.dp))
                            AnimatedContent(targetState = monday, modifier = Modifier.weight(1f),
                                transitionSpec = {
                                    val direction = if (targetState > initialState) 1 else -1
                                    (slideInHorizontally(tween(280)) { direction * it / 6 } + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(tween(280)) { -direction * it / 6 } + fadeOut(tween(180)))
                                }, label = "week") { shownMonday ->
                                WeekBoard(current, shownMonday, today, Modifier.fillMaxSize())
                            }
                        }
                    }
                    1 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("通知仅供查阅，课程安排以重新获取的课表为准。", fontSize = 13.sp, color = Muted)
                        noticeError?.let { Text(it, color = Color(0xFF9D3829), fontSize = 13.sp) }
                        val saved = noticeSnapshot
                        if (saved == null) Text("登录并同步后显示学校调课通知。", color = Ink)
                        else {
                            Text("上次通知同步 · ${formatInstant(saved.retrievedAt)}", color = Muted, fontSize = 12.sp)
                            val notices = remember(saved) { NoticeParser().parse(saved.records) }
                            if (notices.isEmpty()) Text("这次获取的消息中没有调课、停课或补课通知。", color = Ink)
                            notices.forEach { notice ->
                                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                                    Text(notice.publishedAt + if (notice.duplicateCount > 1) " · 合并 ${notice.duplicateCount} 条相同通知" else "", fontSize = 12.sp, color = Muted)
                                    Spacer(Modifier.height(8.dp))
                                    Text(notice.text, color = Ink, fontSize = 14.sp, lineHeight = 22.sp)
                                }
                            }
                        }
                    }
                    2 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
                        Text("从教务系统获取课表", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(16.dp))
                        if (platform.webLoginAvailable) {
                            TextButton("网页登录教务系统", { platform.openWebLogin(account) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                            Spacer(Modifier.height(16.dp))
                        }
                        if (platform.school != null) {
                            LoginField("学号", account, { account = it }, "account")
                            Spacer(Modifier.height(12.dp))
                            LoginField("密码", password, { password = it }, "password", true)
                            Spacer(Modifier.height(16.dp))
                            TextButton(if (busy) "连接中…" else "登录并同步", { loginSchool() }, enabled = !busy && account.isNotBlank() && password.isNotEmpty())
                            Spacer(Modifier.height(12.dp))
                            Text("账号加密保存在本机，打开应用自动登录同步。", fontSize = 13.sp, color = Muted)
                            Spacer(Modifier.height(8.dp))
                            Text("前台每 5 分钟同步，可手动刷新。", fontSize = 13.sp, color = Muted)
                        }
                        if (savedLogin || platform.webLoginAvailable) {
                            Spacer(Modifier.height(12.dp))
                            TextButton("清除已保存登录", {
                                scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            platform.school?.withSession { platform.credentials?.clear(); platform.clearWebSession(); platform.school?.logout() }
                                        }
                                        savedLogin = false; authenticated = false; account = ""; message = "已清除本地登录信息"
                                    } catch (_: Exception) { failure = true; message = "登录信息清理失败，请重试" }
                                    finally { busy = false }
                                }
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(16.dp))
                        TextButton("打开教务系统", { platform.openSchool() }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        TextButton("导入官方 XLS 文件", { importFile() }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        val current = snapshot
                        if (current != null && platform.calendarActionLabel != null) {
                            Spacer(Modifier.height(12.dp))
                            TextButton(platform.calendarActionLabel!!, { platform.importCalendar(current) },
                                modifier = Modifier.fillMaxWidth(), enabled = !busy && current.periods.isNotEmpty())
                        }
                        if (platform.calendarLinked) {
                            Spacer(Modifier.height(12.dp))
                            TextButton("停止日历自动同步", { platform.disableCalendar() }, modifier = Modifier.fillMaxWidth())
                        }
                        if (current != null && platform.moduleSupported) {
                            Spacer(Modifier.height(12.dp))
                            TextButton("开启小爱课表自动管理", { platform.manageXiaoAi(current) },
                                modifier = Modifier.fillMaxWidth(), enabled = !busy && current.periods.isNotEmpty())
                            if (platform.moduleLinked) {
                                Spacer(Modifier.height(12.dp))
                                TextButton("停止小爱自动管理", { platform.disableXiaoAi() }, modifier = Modifier.fillMaxWidth())
                            }
                            val moduleState = platform.moduleStatus?.collectAsState()?.value
                            if (moduleState != null) { Spacer(Modifier.height(8.dp)); Text(moduleState, fontSize = 12.sp, color = Muted) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("如学校要求验证码，请先在学校网页完成验证。也可从信息查询 → 课表查询导出当前学期全部周次 XLS，再手动导入。", fontSize = 13.sp, color = Muted)
                      }
                    }
                }
                  }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun formatInstant(value: String) = runCatching {
    Instant.parse(value).atZone(Shanghai).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value)

@Composable private fun LoginField(label: String, value: String, onChange: (String) -> Unit, tag: String, secret: Boolean = false) {
    TextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().testTag(tag),
        label = label, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)

}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    // Same geometry as design/logo.svg; colours follow the Monet palette so the
    // mark recolours with the system theme on Android and Windows.
    val badge = MiuixTheme.colorScheme.primary
    val glyph = MiuixTheme.colorScheme.onPrimary
    Canvas(modifier) {
        val unit = size.minDimension / 512f
        drawRoundRect(
            color = badge,
            size = Size(size.minDimension, size.minDimension),
            cornerRadius = CornerRadius(112f * unit, 112f * unit),
        )
        fun block(x: Float, y: Float, width: Float, height: Float, corner: Float) {
            drawRoundRect(
                color = glyph,
                topLeft = Offset(x * unit, y * unit),
                size = Size(width * unit, height * unit),
                cornerRadius = CornerRadius(corner * unit, corner * unit),
            )
        }
        block(136f, 116f, 240f, 50f, 25f)
        for (row in 0..2) {
            for (column in 0..1) {
                block(136f + column * 132f, 182f + row * 82f, 108f, 66f, 18f)
            }
        }
    }
}

@Composable
private fun EmptySchedule(openSchool: () -> Unit, importFile: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(28.dp)) {
        BrandMark(Modifier.size(60.dp))
        Spacer(Modifier.height(16.dp))
        Text("暂无课表", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(12.dp))
        Text("请登录教务系统同步，或导入官方 XLS 课表。", color = Muted, lineHeight = 24.sp)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("前往教务系统", openSchool)
            TextButton("选择课表文件", importFile)
        }
    }
}

private data class CourseBlock(val lesson: Lesson, val start: Int, val end: Int, val lane: Int, val lanes: Int)

// Split nonconsecutive sections and place simultaneous classes side by side.
private fun courseBlocks(lessons: List<Lesson>): List<CourseBlock> {
    val spans = lessons.flatMap { lesson ->
        val runs = mutableListOf<Pair<Int, Int>>()
        lesson.sections.distinct().sorted().forEach { section ->
            if (runs.lastOrNull()?.second == section - 1) runs[runs.lastIndex] = runs.last().first to section
            else runs += section to section
        }
        runs.map { CourseBlock(lesson, it.first, it.second, 0, 1) }
    }.sortedBy { it.start }
    val result = mutableListOf<CourseBlock>()
    var group = mutableListOf<CourseBlock>()
    var groupEnd = 0
    fun flush() {
        val ends = mutableListOf<Int>()
        val assigned = group.map { span ->
            val free = ends.indexOfFirst { it < span.start }.let { if (it < 0) ends.size else it }
            if (free == ends.size) ends += span.end else ends[free] = span.end
            span.copy(lane = free)
        }
        result += assigned.map { it.copy(lanes = ends.size) }
        group = mutableListOf()
    }
    spans.forEach {
        if (group.isNotEmpty() && it.start > groupEnd) { flush(); groupEnd = 0 }
        group += it
        groupEnd = maxOf(groupEnd, it.end)
    }
    flush()
    return result
}

@Composable
private fun WeekBoard(snapshot: ScheduleSnapshot, monday: LocalDate, today: LocalDate, modifier: Modifier) {
    var selected by remember(snapshot, monday) { mutableStateOf<Lesson?>(null) }
    val axisWidth = 42.dp
    val rowHeight = 64.dp
    val line = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            Box(Modifier.width(axisWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("节次", fontSize = 10.sp, color = Muted)
            }
            repeat(7) { day ->
                val date = monday.plusDays(day.toLong())
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("周${"一二三四五六日"[day]}", fontSize = 12.sp,
                        color = if (date == today) Accent else Ink, fontWeight = FontWeight.Medium)
                    Text("${date.monthValue}/${date.dayOfMonth}", fontSize = 10.sp,
                        color = if (date == today) Accent else Muted)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
          val gridWidth = maxWidth
          Box(Modifier.fillMaxWidth().height(rowHeight * 14)) {
            Canvas(Modifier.matchParentSize()) {
                val axis = axisWidth.toPx()
                val column = (size.width - axis) / 7
                for (row in 0..14) drawLine(line, Offset(0f, rowHeight.toPx() * row), Offset(size.width, rowHeight.toPx() * row), 1f)
                for (day in 0..6) drawLine(line, Offset(axis + column * day, 0f), Offset(axis + column * day, size.height), 1f)
            }
           Row(Modifier.width(gridWidth)) {
            Column(Modifier.width(axisWidth)) {
                repeat(14) { index ->
                    val period = snapshot.periods.firstOrNull { it.section == index + 1 }
                    Column(Modifier.height(rowHeight).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("${index + 1}", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
                        if (period != null) {
                            Text(period.start, fontSize = 9.sp, color = Muted)
                            Text(period.end, fontSize = 9.sp, color = Muted)
                        }
                    }
                }
            }
            repeat(7) { day ->
                val date = monday.plusDays(day.toLong())
                val blocks = remember(snapshot, date) { courseBlocks(snapshot.lessons.filter { it.date == date.toString() }) }
                BoxWithConstraints(Modifier.weight(1f).height(rowHeight * 14)
                    .background(if (date == today) Accent.copy(alpha = 0.035f) else Color.Transparent)) {
                    blocks.forEach { block ->
                        val width = maxWidth / block.lanes
                        val tint = MiuixTheme.colorScheme.primary
                        Box(Modifier.offset(x = width * block.lane, y = rowHeight * (block.start - 1))
                            .width(width).height(rowHeight * (block.end - block.start + 1)).padding(3.dp)
                            .clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = if (isSystemInDarkTheme()) 0.25f else 0.12f))
                            .clickable { selected = block.lesson }.padding(horizontal = 3.dp, vertical = 6.dp)) {
                            Column(Modifier.fillMaxSize()) {
                                Text(block.lesson.name, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                                    color = Ink, overflow = TextOverflow.Ellipsis, maxLines = if (block.end > block.start) 5 else 2)
                                Spacer(Modifier.height(4.dp))
                                Text(block.lesson.location, fontSize = 9.sp, lineHeight = 12.sp,
                                    color = Muted, overflow = TextOverflow.Ellipsis, maxLines = 3)
                            }
                        }
                    }
                }
            }
           }
          }
        }
    }
    val lesson = selected
    WindowDialog(show = lesson != null, title = lesson?.name, onDismissRequest = { selected = null }) {
        if (lesson != null) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${lesson.date}  第 ${lesson.sections.joinToString("、")} 节", color = Muted, fontSize = 14.sp)
                val times = lesson.sections.mapNotNull { section -> snapshot.periods.firstOrNull { it.section == section } }
                if (times.isNotEmpty()) Text(times.joinToString("\n") { "第 ${it.section} 节  ${it.start}–${it.end}" }, fontSize = 14.sp)
                Text(lesson.location.ifBlank { "教室未提供" })
                if (lesson.teachers.isNotBlank()) Text(lesson.teachers)
                if (lesson.kind.isNotBlank()) Text(lesson.kind, color = Muted, fontSize = 14.sp)
                if (lesson.content.isNotBlank()) Text(lesson.content, fontSize = 14.sp, lineHeight = 22.sp)
            }
            Spacer(Modifier.height(16.dp))
            TextButton("关闭详情", { selected = null }, modifier = Modifier.fillMaxWidth())
        }
    }
}

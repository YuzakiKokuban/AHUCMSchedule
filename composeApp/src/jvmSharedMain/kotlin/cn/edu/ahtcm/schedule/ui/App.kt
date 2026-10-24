package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.withTransform
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.*
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
    val desktop: Boolean get() = false
    val syncGate: SyncGate? get() = null
    val termSelection: TermSelectionStore? get() = null
    val currentTermOnlyIntegrations: Boolean get() = false
    val aboutAvailable: Boolean get() = false
    fun openAbout() = Unit
    val widgetAvailable: Boolean get() = false
    fun addWidget() = Unit
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
    val moduleActivation: StateFlow<Boolean>? get() = null
    val moduleStatus: StateFlow<String>? get() = null
    fun manageXiaoAi(snapshot: ScheduleSnapshot) = Unit
    fun onScheduleSynchronized(snapshot: ScheduleSnapshot): List<String> = emptyList()
    val integrationFeedback: StateFlow<IntegrationFeedback?>? get() = null
    fun consumeFeedback(id: Long) = Unit
    val repository: ScheduleRepository
    val school: SchoolClient? get() = null
    val sessionReady: Boolean get() = school?.isAuthenticated == true
    val noticeStore: NoticeStore? get() = null
    val academicCalendarStore: AcademicCalendarStore? get() = null
    val foreground: StateFlow<Boolean>? get() = null
    fun selectAccount(account: String) = Unit
    fun chooseXls(onResult: (ByteArray?, String?) -> Unit)
    fun openSchool()
    val selections: StateFlow<FileSelection?>? get() = null
    fun consumeSelection(token: Long) = Unit
}

data class IntegrationFeedback(val id: Long, val message: String, val error: Boolean = false)

data class FileSelection(val token: Long, val bytes: ByteArray?, val error: String? = null)

private val Ink: Color @Composable get() = MiuixTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
private val Accent: Color @Composable get() = MiuixTheme.colorScheme.primary
private val Background: Color @Composable get() = MiuixTheme.colorScheme.background
private val Shanghai = ZoneId.of("Asia/Shanghai")

@Composable
fun App(platform: PlatformActions, today: LocalDate = LocalDate.now(Shanghai)) {
    val repository = platform.repository
    var explicitTerm by remember(repository) { mutableStateOf(platform.termSelection?.selected()) }
    var selectedTerm by remember(repository) { mutableStateOf(explicitTerm ?: repository.snapshot()?.let { termFor(it.termCode) }
        ?: platform.academicCalendarStore?.availableTerms()?.let { catalog -> catalog.terms.firstOrNull { it.code == catalog.currentCode } }) }
    fun selectedSnapshot() = if (selectedTerm != null) platform.repository.snapshot(selectedTerm!!.code) else platform.repository.snapshot()
    var snapshot by remember(repository) { mutableStateOf(selectedSnapshot()) }
    var calendarSnapshot by remember(repository) { mutableStateOf(selectedTerm?.let { platform.academicCalendarStore?.snapshot(it.code) }) }
    var calendarError by remember(repository) { mutableStateOf(platform.academicCalendarStore?.loadError) }
    var terms by remember(repository) { mutableStateOf(knownTerms(platform)) }
    var pickingTerm by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(0) }
    val pageStateHolder = key(repository) { rememberSaveableStateHolder() }
    val syncGate = remember(platform) { platform.syncGate ?: SyncGate() }
    val sharedSyncing by syncGate.running.collectAsState()
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

    var monday by rememberSaveable(repository, selectedTerm?.code, stateSaver = Saver<LocalDate, String>(save = { it.toString() }, restore = { LocalDate.parse(it) })) {
        mutableStateOf(if (snapshot != null && today.toString() !in snapshot!!.weekOneMonday..snapshot!!.calendarEndDate) LocalDate.parse(snapshot!!.weekOneMonday)
            else today.minusDays((today.dayOfWeek.value - 1).toLong()))
    }
    val generation = platform.sessionGeneration?.collectAsState()?.value ?: 0L
    LaunchedEffect(repository, generation) {
        explicitTerm = platform.termSelection?.selected()
        selectedTerm = explicitTerm ?: repository.snapshot()?.let { termFor(it.termCode) }
            ?: platform.academicCalendarStore?.availableTerms()?.let { catalog -> catalog.terms.firstOrNull { it.code == catalog.currentCode } }
        snapshot = selectedSnapshot()
        calendarSnapshot = selectedTerm?.let { platform.academicCalendarStore?.snapshot(it.code) }
        terms = knownTerms(platform)
        if (platform.sessionReady) {
            authenticated = true
            if (!syncGate.running.value) syncGate.nextCheckMillis = 0L
            snapshot = selectedSnapshot()
            noticeSnapshot = platform.noticeStore?.snapshot()
        }
    }
    val scope = rememberCoroutineScope()
    suspend fun syncSchool(expectedOwner: ScheduleRepository = repository) {
        if (platform.repository !== expectedOwner) return
        if (!platform.sessionReady) {
            authenticated = false
            message = "请重新登录后同步；当前账号尚未完成连接。"
            failure = true
            return
        }
        if (busy || !syncGate.acquire()) return
        val owner = expectedOwner
        busy = true
        syncing = true
        syncGate.nextCheckMillis = System.currentTimeMillis() + 5 * 60 * 1000L
        try {
            val refreshed = synchronizeSchool(platform, explicitTerm, owner)
            if (platform.repository !== owner) return
            if (explicitTerm == null) selectedTerm = refreshed.term ?: selectedTerm
            snapshot = selectedSnapshot()
            calendarSnapshot = selectedTerm?.let { platform.academicCalendarStore?.snapshot(it.code) }
            calendarError = refreshed.calendarError
            terms = knownTerms(platform)
            val errors = buildList {
                (refreshed.schedule as? UpdateResult.Failure)?.let { add(it.message + if (snapshot != null) "；已保留这个学期的课表。" else "；这个学期暂无可用课表。") }
                refreshed.calendarError?.let { add(it) }
                addAll(refreshed.integrationErrors)
            }
            failure = errors.isNotEmpty()
            message = if (errors.isEmpty()) null else errors.joinToString("\n")
            noticeSnapshot = refreshed.notices
            noticeError = refreshed.noticeError
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            failure = true
            message = "同步未完成，请检查网络或重新连接教务；已保存的数据保留。"
        } finally { busy = false; syncing = false; syncGate.release() }
    }
    LaunchedEffect(authenticated, foreground, repository, generation) {
        if (authenticated && foreground) {
            snapshot = selectedSnapshot()
            noticeSnapshot = platform.noticeStore?.snapshot()
            if (!syncGate.running.value) syncGate.nextCheckMillis = 0L
        }
    }
    LaunchedEffect(authenticated, repository, generation) {
        if (authenticated) {
            while (isActive) {
                if (currentForeground && !busy && !syncGate.running.value && System.currentTimeMillis() >= syncGate.nextCheckMillis) {
                    syncGate.nextCheckMillis = System.currentTimeMillis() + 5 * 60 * 1000L
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
            snapshot = selectedSnapshot()
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
                    snapshot = selectedSnapshot()
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
                snapshot = selectedSnapshot()
                message = feedback.message; failure = feedback.error
                platform.consumeFeedback(feedback.id)
            }
        }
    }
    fun loginSchool() {
        if (busy || syncGate.running.value) return
        val login = SavedLogin(account.trim(), password)
        password = ""
        scope.launch { authenticate(login, true) }
    }
    suspend fun receiveFile(bytes: ByteArray?, error: String?) {
        val owner = platform.repository
        try {
            if (error != null) { failure = true; message = error; return }
            if (bytes == null) return
            busy = true
            val result = withContext(Dispatchers.IO) { owner.importXls(bytes) }
            if (platform.repository !== owner) return
            when (result) {
                is UpdateResult.Success -> {
                    val importedTerm = termFor(result.snapshot.termCode)
                    var preferenceFailed = false
                    try { if (importedTerm != null) persistTermSelection(platform, importedTerm, owner) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { preferenceFailed = true }
                    if (platform.repository !== owner) return
                    selectedTerm = importedTerm
                    explicitTerm = importedTerm
                    snapshot = result.snapshot
                    calendarSnapshot = importedTerm?.let { platform.academicCalendarStore?.snapshot(it.code) }
                    terms = knownTerms(platform)
                    failure = preferenceFailed
                    message = if (preferenceFailed) "课表已导入，但学期选择无法保存，下次打开可能需要重新选择" else null
                }
                is UpdateResult.Failure -> { failure = true; message = result.message + "；原有课表已保留。" }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failure = true; message = "文件导入未完成，请重试" }
        finally { busy = false }
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
        if (busy || syncGate.running.value) return
        busy = true
        platform.chooseXls { bytes, error ->
            scope.launch { receiveFile(bytes, error) }
        }
    }
    val theme = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    val motionTravel = with(LocalDensity.current) { 80.dp.roundToPx() }
    MiuixTheme(controller = theme) {
        AdaptiveAppScaffold(desktop = platform.desktop, page = page, onNavigate = { page = it },
            status = if (authenticated) "已连接教务 · 前台自动同步" else "离线查看", topBar = {
                AppTopBar(platform.desktop, page, syncing || sharedSyncing, !busy && !sharedSyncing) {
                    if (authenticated) scope.launch { syncSchool() }
                    else { page = 2; message = "请登录教务系统，之后打开应用会自动同步"; failure = false }
                }
            },
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
                AnimatedContent(targetState = page, modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    transitionSpec = {
                        directionalSlide(navigationPosition(targetState) > navigationPosition(initialState), motionTravel)
                    }, label = "page") { destination ->
                  pageStateHolder.SaveableStateProvider(destination) {
                  Column(Modifier.fillMaxSize()) {
                   when (destination) {
                    0 -> {
                        val current = snapshot
                        if (current == null) {
                            selectedTerm?.let { Text(it.label, fontSize = 13.sp, color = Muted); Text("这个学期还没有缓存课表", color = Muted) }
                            EmptySchedule({ platform.openSchool() }, { importFile() })
                        } else {
                            val week = ChronoUnit.WEEKS.between(LocalDate.parse(current.weekOneMonday), monday) + 1
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    if (!LocalAppLayout.current.shortWindow) Text(current.termLabel, fontSize = 12.sp, color = Muted)
                                    Text("${monday.monthValue}/${monday.dayOfMonth} — ${monday.plusDays(6).monthValue}/${monday.plusDays(6).dayOfMonth}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
                                    Text(if (week > 0 && monday.toString() <= current.calendarEndDate) "第 $week 周" else "所选日期不在导入的教学周内", color = Muted, fontSize = 12.sp)
                                }
                                PeriodNavigation("本周", "上一周", "下一周", { monday = monday.minusWeeks(1) },
                                    { monday = today.minusDays((today.dayOfWeek.value - 1).toLong()) }, { monday = monday.plusWeeks(1) })
                            }
                            Spacer(Modifier.height(if (LocalAppLayout.current.shortWindow) 2.dp else 10.dp))
                            val formatted = runCatching {
                                Instant.parse(current.retrievedAt).atZone(Shanghai).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                            }.getOrDefault(current.retrievedAt)
                            Text(if (current.source == "school") "上次教务同步 · $formatted${if (!authenticated) "  ／  请登录检查更新" else ""}" else "文件导入 · $formatted  ／  请同步确认最新安排", fontSize = 12.sp, color = Muted)
                            if (current.periods.isEmpty() && !LocalAppLayout.current.shortWindow) Text("作息时间待同步", fontSize = 11.sp, color = Muted)
                            Spacer(Modifier.height(if (LocalAppLayout.current.shortWindow) 2.dp else 8.dp))
                            AnimatedContent(targetState = monday, modifier = Modifier.weight(1f).clipToBounds(),
                                transitionSpec = {
                                    directionalSlide(targetState > initialState, motionTravel)
                                }, label = "week") { shownMonday ->
                                WeekBoard(current, shownMonday, today, Modifier.fillMaxSize())
                            }
                        }
                    }
                    3 -> AcademicCalendarPanel(calendarSnapshot, selectedTerm, authenticated, busy || sharedSyncing, calendarError, today, Modifier.weight(1f), onConnect = { page = 2 })
                    1 -> NoticePanel(noticeSnapshot, noticeError, Modifier.weight(1f))
                    2 -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                      Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                        Text("学期", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(selectedTerm?.label ?: "连接教务后获取学期", color = Muted)
                        TextButton("选择学期", { pickingTerm = true }, enabled = !busy && !sharedSyncing && terms.isNotEmpty())
                        Text("同时控制课表和校历", fontSize = 13.sp, color = Muted)
                      }
                      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
                        Text("从教务系统获取课表", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(16.dp))
                        if (platform.webLoginAvailable) {
                            TextButton("网页登录教务系统", { platform.openWebLogin(account) }, modifier = Modifier.fillMaxWidth(), enabled = !busy && !sharedSyncing)
                            Spacer(Modifier.height(16.dp))
                        }
                        if (platform.school != null) {
                            LoginField("学号", account, { account = it }, "account")
                            Spacer(Modifier.height(12.dp))
                            LoginField("密码", password, { password = it }, "password", true)
                            Spacer(Modifier.height(16.dp))
                            TextButton(if (busy) "连接中…" else "登录并同步", { loginSchool() }, enabled = !busy && !sharedSyncing && account.isNotBlank() && password.isNotEmpty())
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
                            }, enabled = !busy && !sharedSyncing, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(16.dp))
                        TextButton("打开教务系统", { platform.openSchool() }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        TextButton("导入官方 XLS 文件", { importFile() }, modifier = Modifier.fillMaxWidth(), enabled = !busy && !sharedSyncing)
                        if (platform.widgetAvailable) {
                            Spacer(Modifier.height(12.dp))
                            TextButton("创建桌面小组件", { platform.addWidget() }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("在主屏幕查看今日课表，随本机课表更新；创建时由系统确认。", fontSize = 13.sp, color = Muted)
                        }
                        val current = snapshot?.takeIf { !platform.currentTermOnlyIntegrations || it.termCode == platform.academicCalendarStore?.availableTerms()?.currentCode }
                        if (snapshot != null && current == null && platform.calendarActionLabel != null) Text("系统日历和小爱自动管理仅用于学校当前学期", fontSize = 13.sp, color = Muted)
                        if (current != null && platform.calendarActionLabel != null) {
                            Spacer(Modifier.height(12.dp))
                            TextButton(platform.calendarActionLabel!!, { platform.importCalendar(current) },
                                modifier = Modifier.fillMaxWidth(), enabled = !busy && current.periods.isNotEmpty())
                        }
                        if (platform.calendarLinked) {
                            Spacer(Modifier.height(12.dp))
                            TextButton("停止日历自动同步", { platform.disableCalendar() }, modifier = Modifier.fillMaxWidth())
                        }
                        val moduleActive = platform.moduleActivation?.collectAsState()?.value == true
                        if (current != null && platform.moduleSupported && moduleActive) {
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
                        if (platform.aboutAvailable) {
                            Spacer(Modifier.height(16.dp))
                            TextButton("关于岐黄课表", { platform.openAbout() }, modifier = Modifier.fillMaxWidth())
                        }
                      }
                      }
                    }
                }
                  }
                  }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    WindowDialog(show = pickingTerm, title = "选择学期", onDismissRequest = { pickingTerm = false }) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            terms.forEach { term ->
                TextButton(term.label, {
                    pickingTerm = false
                    scope.launch {
                        if (busy || !syncGate.acquire()) return@launch
                        val owner = repository
                        busy = true
                        var refresh = false
                        try {
                            persistTermSelection(platform, term, owner)
                            explicitTerm = term
                            selectedTerm = term
                            snapshot = owner.snapshot(term.code)
                            calendarSnapshot = platform.academicCalendarStore?.snapshot(term.code)
                            calendarError = null
                            message = null
                            refresh = platform.sessionReady
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { message = "学期选择保存失败，请重试"; failure = true }
                        finally { busy = false; syncGate.release() }
                        if (refresh && platform.repository === owner) syncSchool(owner)
                    }
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    }
}

internal fun formatInstant(value: String) = runCatching {
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
    val paths = remember { BrandMarkPaths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier) {
        val unit = size.minDimension / 512f
        drawRoundRect(
            color = badge,
            size = Size(size.minDimension, size.minDimension),
            cornerRadius = CornerRadius(112f * unit, 112f * unit),
        )
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            paths.forEach { drawPath(it, glyph) }
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
    val layout = LocalAppLayout.current
    var selectedKey by rememberSaveable(snapshot.termCode, monday.toString()) { mutableStateOf<String?>(null) }
    val selected = snapshot.lessons.firstOrNull { lessonSelectionKey(it) == selectedKey }
    val axisWidth = if (layout.navigation == NavigationMode.Bottom) 42.dp else 54.dp
    val rowHeight = layout.rowHeight
    val line = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Row(modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && selectedKey != null) { selectedKey = null; true } else false
    }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(if (layout.shortWindow) 36.dp else 44.dp)) {
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
                            .clickable { selectedKey = lessonSelectionKey(block.lesson) }.padding(horizontal = 3.dp, vertical = 6.dp)) {
                            Column(Modifier.fillMaxSize()) {
                                Text(block.lesson.name, fontSize = if (layout.navigation == NavigationMode.Bottom) 11.sp else 13.sp, lineHeight = if (layout.navigation == NavigationMode.Bottom) 14.sp else 17.sp, fontWeight = FontWeight.Medium,
                                    color = Ink, overflow = TextOverflow.Ellipsis, maxLines = if (block.end > block.start) 5 else 2)
                                Spacer(Modifier.height(4.dp))
                                Text(block.lesson.location, fontSize = if (layout.navigation == NavigationMode.Bottom) 9.sp else 11.sp, lineHeight = 14.sp,
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
    if (layout.details) Column(Modifier.width(288.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surface).padding(20.dp).testTag("course-detail-pane")) {
        Text("课程详情", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (selected == null) Text("选择课程查看完整安排", color = Muted)
        else LessonDetails(selected, snapshot, { selectedKey = null }, Modifier.weight(1f))
    }
    }
    WindowDialog(show = selected != null && !layout.details, title = selected?.name, onDismissRequest = { selectedKey = null }) {
        selected?.let { LessonDetails(it, snapshot, { selectedKey = null }, Modifier.heightIn(max = 420.dp)) }
    }
}

private fun lessonSelectionKey(lesson: Lesson) = listOf(lesson.date, lesson.sections.joinToString(","), lesson.name, lesson.teachers, lesson.location).joinToString("\u001f")

@Composable
private fun LessonDetails(lesson: Lesson, snapshot: ScheduleSnapshot, close: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(lesson.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("${lesson.date}  第 ${lesson.sections.joinToString("、")} 节", color = Muted, fontSize = 14.sp)
        val times = lesson.sections.mapNotNull { section -> snapshot.periods.firstOrNull { it.section == section } }
        if (times.isNotEmpty()) Text(times.joinToString("\n") { "第 ${it.section} 节  ${it.start}–${it.end}" }, fontSize = 14.sp)
        Text(lesson.location.ifBlank { "教室未提供" })
        if (lesson.teachers.isNotBlank()) Text(lesson.teachers)
        if (lesson.kind.isNotBlank()) Text(lesson.kind, color = Muted, fontSize = 14.sp)
        if (lesson.content.isNotBlank()) Text(lesson.content, fontSize = 14.sp, lineHeight = 22.sp)
        TextButton("关闭详情", close, modifier = Modifier.fillMaxWidth())
    }
}

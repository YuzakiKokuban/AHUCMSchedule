package com.yuzakikokuban.ahucm.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.yuzakikokuban.ahucm.BuildConfig
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward

class UpdateFileProvider : FileProvider()

class AboutActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[UpdateViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme = remember { ThemeController(ColorSchemeMode.MonetSystem) }
            val state by model.state.collectAsState()
            var document by remember { mutableStateOf<String?>(null) }
            var licenseText by remember { mutableStateOf("") }
            BackHandler(enabled = document != null) { document = null }
            val scope = rememberCoroutineScope()
            LaunchedEffect(document) {
                licenseText = when (document) {
                    "开源许可证" -> assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
                    "第三方开源声明" -> assets.open("third-party-notices.txt").bufferedReader().use { it.readText() }
                    else -> ""
                }
            }
            MiuixTheme(controller = theme) {
                Scaffold(topBar = { SmallTopAppBar(title = document ?: "关于岐黄课表", navigationIconPadding = 12.dp, navigationIcon = {
                    IconButton(onClick = { if (document != null) document = null else finish() }, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.ChevronBackward, contentDescription = "返回", modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.onSurface)
                    }
                }) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (document != null) {
                            Text(licenseText, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                        } else {
                            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
                                Text("岐黄课表", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                                Spacer(Modifier.height(12.dp))
                                Text("© 2026 YuzakiKokuban")
                                Text("本项目原创代码与图标采用 Apache License 2.0。", fontSize = 14.sp)
                                Text("安徽中医药大学课表工具 · 非学校官方应用", fontSize = 13.sp)
                            }
                            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                                Text(state.message)
                                state.release?.let { release ->
                                    Spacer(Modifier.height(12.dp))
                                    Text("版本 ${release.version} · ${"%.1f".format(release.size / 1024.0 / 1024.0)} MB", fontWeight = FontWeight.Bold)
                                    if (release.notes.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(release.notes, fontSize = 14.sp) }
                                }
                                state.progress?.let { Text("下载进度 $it%", modifier = Modifier.padding(top = 12.dp)) }
                                Spacer(Modifier.height(12.dp))
                                TextButton("检查更新", { model.checkUpdate() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                                if (state.release != null && !state.ready) {
                                    Spacer(Modifier.height(12.dp))
                                    TextButton("下载更新", { model.download() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                                }
                                if (state.ready) {
                                    Spacer(Modifier.height(12.dp))
                                    TextButton("安装更新", {
                                        scope.launch {
                                            try {
                                                val apk = model.verifiedApk()
                                                if (!packageManager.canRequestPackageInstalls()) {
                                                    model.installationMessage("请允许本应用安装更新，返回后再次点击“安装更新”")
                                                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                                                } else {
                                                    val uri = FileProvider.getUriForFile(this@AboutActivity, "$packageName.updates", apk)
                                                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                                }
                                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                            catch (_: Exception) { model.installationFailed() }
                                        }
                                    }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                            TextButton("项目主页", {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseUpdate.PROJECT))) }
                                    .onFailure { model.installationMessage("无法打开浏览器") }
                            }, modifier = Modifier.fillMaxWidth())
                            TextButton("开源许可证", { document = "开源许可证" }, modifier = Modifier.fillMaxWidth())
                            TextButton("第三方开源声明", { document = "第三方开源声明" }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

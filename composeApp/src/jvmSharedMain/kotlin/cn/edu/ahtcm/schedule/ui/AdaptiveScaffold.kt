package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class NavigationMode { Bottom, Rail, Sidebar }
internal data class AppLayout(val navigation: NavigationMode, val details: Boolean, val shortWindow: Boolean, val navigationWidth: Float = 72f, val tallTablet: Boolean = false) {
    val rowHeight get() = if (shortWindow) 52.dp else if (navigation == NavigationMode.Bottom) 64.dp else 60.dp
}
internal fun appLayout(width: Float, height: Float, desktop: Boolean, fontScale: Float = 1f): AppLayout {
    val nav = when {
        desktop && width >= 1000 -> NavigationMode.Sidebar
        desktop || width >= 600 -> NavigationMode.Rail
        else -> NavigationMode.Bottom
    }
    val navigationWidth = when (nav) { NavigationMode.Sidebar -> 192f; NavigationMode.Rail -> (72 * fontScale.coerceAtLeast(1f)).coerceAtMost(112f); else -> 0f }
    return AppLayout(nav, width - navigationWidth >= 900 * fontScale.coerceAtLeast(1f) && height >= 480, height < 480, navigationWidth, width >= 600 && height >= 1000)
}
internal val LocalAppLayout = staticCompositionLocalOf { AppLayout(NavigationMode.Bottom, false, false) }

/** Shared by platform lifetime, so rotation cannot start another in-flight sync. */
class SyncGate {
    private val mutable = MutableStateFlow(false)
    val running = mutable.asStateFlow()
    @Volatile var nextCheckMillis: Long = 0L
    @Synchronized fun acquire(): Boolean {
        if (mutable.value) return false
        mutable.value = true
        return true
    }
    @Synchronized fun release() { mutable.value = false }
}

private data class AppDestination(val page: Int, val label: String)
private val destinations = listOf(AppDestination(0, "我的课表"), AppDestination(3, "校历"), AppDestination(1, "调课通知"), AppDestination(2, "连接教务"))
internal fun navigationPosition(page: Int): Int = destinations.indexOfFirst { it.page == page }.also { require(it >= 0) }

@Composable
internal fun AdaptiveAppScaffold(
    desktop: Boolean, page: Int, onNavigate: (Int) -> Unit,
    status: String, topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = appLayout(maxWidth.value, maxHeight.value, desktop, LocalDensity.current.fontScale)
        CompositionLocalProvider(LocalAppLayout provides layout) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                if (layout.navigation != NavigationMode.Bottom) {
                    val expanded = layout.navigation == NavigationMode.Sidebar
                    Column(Modifier.width(layout.navigationWidth.dp).fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
                        .background(MiuixTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = if (layout.shortWindow) 8.dp else 20.dp)
                        .testTag(if (expanded) "desktop-sidebar" else "navigation-rail")) {
                        if (expanded) {
                            Text("岐黄课表", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                            Spacer(Modifier.height(24.dp))
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            destinations.forEach { item ->
                                val label = item.label
                                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (page == item.page) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                    .selectable(page == item.page, role = Role.Tab, onClick = { onNavigate(item.page) })
                                    .testTag("destination-${item.page}").padding(horizontal = if (expanded) 14.dp else 2.dp, vertical = 14.dp),
                                    contentAlignment = if (expanded) Alignment.CenterStart else Alignment.Center) {
                                    Text(label, fontSize = if (expanded) 15.sp else 13.sp,
                                        fontWeight = if (page == item.page) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        if (expanded) Text(status, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(12.dp))
                    }
                }
                Scaffold(Modifier.weight(1f).fillMaxHeight(), topBar = topBar, bottomBar = {
                    if (layout.navigation == NavigationMode.Bottom) NavigationBar(showDivider = false, modifier = Modifier.testTag("bottom-navigation")) {
                        val icons = listOf(MiuixIcons.Months, MiuixIcons.Months, MiuixIcons.Messages, MiuixIcons.Link)
                        destinations.forEachIndexed { i, item ->
                            NavigationBarItem(selected = page == item.page, onClick = { onNavigate(item.page) }, icon = icons[i], label = item.label, modifier = Modifier.testTag("destination-${item.page}"))
                        }
                    }
                }, content = content)
            }
        }
    }
}

@Composable
internal fun AppTopBar(desktop: Boolean, page: Int, syncing: Boolean, enabled: Boolean, onSync: () -> Unit) {
    val title = if (desktop) when (page) { 0 -> "我的课表"; 1 -> "调课通知"; 2 -> "连接教务"; else -> "校历" } else "岐黄课表"
    val action: @Composable () -> Unit = {
        val rotation = if (syncing) rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing))).value else 0f
        IconButton(onClick = onSync, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(MiuixIcons.Refresh, contentDescription = "同步", modifier = Modifier.size(24.dp).rotate(rotation))
        }
    }
    if (LocalAppLayout.current.shortWindow) Row(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)).height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        action()
    } else SmallTopAppBar(title = title, titlePadding = 16.dp, actionIconPadding = 12.dp, actions = { action() })
}

@Composable
internal fun PeriodNavigation(
    current: String, previousDescription: String, nextDescription: String,
    onPrevious: () -> Unit, onCurrent: () -> Unit, onNext: () -> Unit,
    previousEnabled: Boolean = true, nextEnabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = previousEnabled, modifier = Modifier.size(44.dp)) {
            Icon(MiuixIcons.ChevronBackward, contentDescription = previousDescription, modifier = Modifier.size(20.dp))
        }
        TextButton(current, onCurrent, minWidth = 48.dp, minHeight = 40.dp, insideMargin = PaddingValues(horizontal = 8.dp))
        IconButton(onClick = onNext, enabled = nextEnabled, modifier = Modifier.size(44.dp)) {
            Icon(MiuixIcons.ChevronForward, contentDescription = nextDescription, modifier = Modifier.size(20.dp))
        }
    }
}

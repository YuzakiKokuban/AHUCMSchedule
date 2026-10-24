package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.ahtcm.schedule.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AcademicCalendarPanel(
    snapshot: AcademicCalendarSnapshot?, term: AcademicTerm?, authenticated: Boolean,
    loading: Boolean, error: String?, today: LocalDate, modifier: Modifier = Modifier, onConnect: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val layout = LocalAppLayout.current
    val motionTravel = with(LocalDensity.current) { 80.dp.roundToPx() }
    val months = remember(snapshot) { snapshot?.days.orEmpty().map { YearMonth.from(LocalDate.parse(it.date)) }.distinct().sorted() }
    var shownMonth by rememberSaveable(term?.code) { mutableStateOf(YearMonth.from(today).toString()) }
    val month = months.firstOrNull { it.toString() == shownMonth } ?: months.firstOrNull()
    var selectedDate by rememberSaveable(term?.code) { mutableStateOf<String?>(null) }
    val selected = snapshot?.days?.firstOrNull { it.date == selectedDate }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (layout.shortWindow) 4.dp else 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(month?.let { "${it.year}年${it.monthValue}月" } ?: "校历", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                term?.let { Text(it.label, fontSize = 12.sp, color = colors.onSurfaceVariantSummary) }
            }
            if (month != null) PeriodNavigation("本月", "上一月", "下一月",
                { shownMonth = month.minusMonths(1).toString() },
                { shownMonth = if (YearMonth.from(today) in months) YearMonth.from(today).toString() else months.first().toString() },
                { shownMonth = month.plusMonths(1).toString() }, month > months.first(), month < months.last())
        }
        if (snapshot != null) {
            val timestamp = runCatching { Instant.parse(snapshot.retrievedAt).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrDefault(snapshot.retrievedAt)
            Text("上次校历同步 · $timestamp", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
        }
        if (!authenticated && !layout.shortWindow) Text("离线查看 · 连接教务后可检查更新", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
        error?.let { Text(it, fontSize = 13.sp, color = colors.onSurface) }
        if (snapshot == null || month == null) {
            Text(if (loading) "正在同步校历…" else "这个学期还没有缓存校历")
            if (!authenticated) TextButton("前往连接教务", onConnect)
        } else Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedContent(targetState = month, modifier = Modifier.fillMaxWidth().clipToBounds(),
                    transitionSpec = { directionalSlide(targetState > initialState, motionTravel) }, label = "calendar-month") { pageMonth ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val twoMonths = maxWidth >= 720.dp && pageMonth < months.last()
                        if (twoMonths || !layout.tallTablet || pageMonth == months.last()) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            AcademicMonthGrid(snapshot, pageMonth, today, selectedDate, { selectedDate = it.date }, Modifier.weight(1f), twoMonths)
                            if (twoMonths) AcademicMonthGrid(snapshot, pageMonth.plusMonths(1), today, selectedDate, { selectedDate = it.date }, Modifier.weight(1f), true)
                        }
                        if (!twoMonths && layout.tallTablet && pageMonth < months.last()) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                AcademicMonthGrid(snapshot, pageMonth, today, selectedDate, { selectedDate = it.date }, Modifier.fillMaxWidth(), false)
                                AcademicMonthGrid(snapshot, pageMonth.plusMonths(1), today, selectedDate, { selectedDate = it.date }, Modifier.fillMaxWidth(), true)
                            }
                        }
                    }
                }
                Text("带圆点的日期有学校备注，点击查看。上课安排以个人课表为准。", fontSize = 12.sp, color = colors.onSurfaceVariantSummary, lineHeight = 19.sp)
                if (!authenticated) TextButton("前往连接教务", onConnect)
            }
            if (layout.details) Column(Modifier.width(288.dp).fillMaxHeight().background(colors.surface, RoundedCornerShape(16.dp)).padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("日期详情", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                if (selected == null) Text("选择日期查看学校备注", color = colors.onSurfaceVariantSummary)
                else CalendarDateDetails(selected) { selectedDate = null }
            }
        }
    }
    WindowDialog(show = selected != null && !layout.details, title = selected?.date, onDismissRequest = { selectedDate = null }) {
        selected?.let { day -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { CalendarDateDetails(day) { selectedDate = null } } }
    }
}

@Composable
private fun CalendarDateDetails(day: AcademicCalendarDay, close: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(day.date, fontSize = 16.sp)
        Text("第 ${day.week} 教学周", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(day.note.ifBlank { "学校未提供日期备注" }, fontSize = 16.sp, lineHeight = 24.sp)
        TextButton("关闭", close, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AcademicMonthGrid(snapshot: AcademicCalendarSnapshot, month: YearMonth, today: LocalDate, selected: String?, onSelect: (AcademicCalendarDay) -> Unit, modifier: Modifier, showTitle: Boolean) {
    val colors = MiuixTheme.colorScheme
    val days = remember(snapshot) { snapshot.days.associateBy { LocalDate.parse(it.date) } }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text("${month.year}年${month.monthValue}月", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth()) {
            Text("教学周", modifier = Modifier.width(44.dp), fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
            "一二三四五六日".forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("$it", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) } }
        }
        val first = month.atDay(1)
        val monday = first.minusDays((first.dayOfWeek.value - 1).toLong())
        repeat((first.dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7) { row ->
            val dates = (0..6).map { monday.plusDays((row * 7 + it).toLong()) }
            val week = dates.firstNotNullOfOrNull { date -> days[date]?.week }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(44.dp)) { Text(week?.toString() ?: "—", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
                dates.forEach { date ->
                    val day = days[date]
                    Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp), contentAlignment = Alignment.Center) {
                        if (YearMonth.from(date) == month) {
                            val isToday = date == today
                            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                .background(if (isToday || date.toString() == selected) colors.primary.copy(alpha = 0.12f) else colors.surface)
                                .then(if (isToday) Modifier.border(1.dp, colors.primary, RoundedCornerShape(10.dp)) else Modifier)
                                .then(if (day != null) Modifier.testTag("academic-day-$date").semantics {
                                    contentDescription = "$date，第 ${day.week} 教学周${if (day.note.isNotBlank()) "，有学校备注" else ""}"
                                }.clickable { onSelect(day) } else Modifier)
                                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("${date.dayOfMonth}", fontSize = 15.sp, color = if (isToday) colors.primary else if (day != null) colors.onSurface else colors.onSurfaceVariantSummary.copy(alpha = 0.45f), fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                                Box(Modifier.height(6.dp), contentAlignment = Alignment.Center) {
                                    if (day?.note?.isNotBlank() == true) Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.primary))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

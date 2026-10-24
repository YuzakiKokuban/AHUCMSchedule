package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.ahtcm.schedule.core.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun NoticePanel(snapshot: NoticeSnapshot?, error: String?, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val split = LocalAppLayout.current.details
    val notices = remember(snapshot) { snapshot?.let { NoticeParser().parse(it.records) }.orEmpty() }
    var selectedText by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = notices.firstOrNull { it.text == selectedText }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("通知仅供查阅，课程安排以重新获取的课表为准。", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
        error?.let { Text(it, fontSize = 13.sp) }
        if (snapshot == null) Text("登录并同步后显示学校调课通知。")
        else {
            Text("上次通知同步 · ${formatInstant(snapshot.retrievedAt)}", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            if (notices.isEmpty()) Text("这次获取的消息中没有调课、停课或补课通知。")
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    notices.forEach { notice ->
                        Card(Modifier.fillMaxWidth().then(if (split) Modifier.clickable { selectedText = notice.text } else Modifier), insideMargin = PaddingValues(18.dp)) {
                            Text(notice.publishedAt + if (notice.duplicateCount > 1) " · 合并 ${notice.duplicateCount} 条相同通知" else "", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            Spacer(Modifier.height(8.dp))
                            Text(notice.text, fontSize = 14.sp, lineHeight = 22.sp, maxLines = if (split) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (split) Card(Modifier.weight(1f).fillMaxHeight(), insideMargin = PaddingValues(24.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("通知原文", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (selected == null) Text("选择左侧通知查看完整内容", color = colors.onSurfaceVariantSummary)
                        else { Text(selected.publishedAt, fontSize = 12.sp); Text(selected.text, fontSize = 16.sp, lineHeight = 26.sp) }
                    }
                }
            }
        }
    }
}

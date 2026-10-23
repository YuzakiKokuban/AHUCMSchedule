package cn.edu.ahtcm.schedule.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import cn.edu.ahtcm.schedule.core.*
import cn.edu.ahtcm.schedule.ui.IntegrationFeedback
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import cn.edu.ahtcm.schedule.core.ScheduleRepository
import cn.edu.ahtcm.schedule.core.SchoolClient
import cn.edu.ahtcm.schedule.core.NoticeStore
import cn.edu.ahtcm.schedule.core.AccountStorage
import cn.edu.ahtcm.schedule.ui.App
import cn.edu.ahtcm.schedule.ui.PlatformActions
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
    val directory = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "AHUCMSchedule")
    val storage = AccountStorage(directory)
    val feedback = MutableStateFlow<IntegrationFeedback?>(null)
    val sequence = AtomicLong()
    val actions = object : PlatformActions {
        override val credentials = EncryptedCredentialStore(directory.resolve("login.bin"), WindowsCredentialProtector())
        override val integrationFeedback = feedback
        override fun consumeFeedback(id: Long) { if(feedback.value?.id==id) feedback.value=null }
        override val calendarActionLabel = "导出日历 ICS"
        override fun importCalendar(snapshot: ScheduleSnapshot) {
            SwingUtilities.invokeLater {
                val picker = JFileChooser().apply { dialogTitle="导出课程日历";selectedFile=java.io.File("安中课表-${snapshot.termCode}.ics");fileFilter=FileNameExtensionFilter("日历 (*.ics)","ics") }
                if(picker.showSaveDialog(null)!=JFileChooser.APPROVE_OPTION) return@invokeLater
                try {
                    val selected = picker.selectedFile
                    val file = if(selected.name.endsWith(".ics",true)) selected else java.io.File(selected.parentFile,selected.name+".ics")
                    Files.writeString(file.toPath(),CalendarExport.ics(snapshot,storage.activeAccountKey ?: "manual"))
                    runCatching { Desktop.getDesktop().open(file) }
                    feedback.value=IntegrationFeedback(sequence.incrementAndGet(),"日历文件已保存，请在日历软件中导入")
                } catch(_:Exception) { feedback.value=IntegrationFeedback(sequence.incrementAndGet(),"日历导出失败，请检查保存位置与作息时间",true) }
            }
        }
        override val repository get() = storage.schedule
        override val school = SchoolClient()
        override val sessionReady get() = storage.matchesSession(school)
        override val noticeStore get() = storage.notices
        override fun selectAccount(account: String) { storage.select(account) }
        override fun openSchool() { Desktop.getDesktop().browse(URI("https://jwxt.ahtcm.edu.cn/new/welcome.page?ui=new")) }
        override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) {
            SwingUtilities.invokeLater {
                val picker = JFileChooser().apply {
                    dialogTitle = "导入教务系统导出的 XLS 课表"
                    fileFilter = FileNameExtensionFilter("教务课表 (*.xls)", "xls")
                }
                if (picker.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) { onResult(null, null); return@invokeLater }
                val file = picker.selectedFile
                if (file.length() > 20L * 1024 * 1024) onResult(null, "文件超过 20 MB，请选择教务系统导出的课表")
                else runCatching { file.readBytes() }.fold({ onResult(it, null) }, { onResult(null, "无法读取所选文件") })
            }
        }
    }
    application {
        val windowIcon = painterResource("icon.png")
        Window(icon = windowIcon, onCloseRequest = ::exitApplication, title = "安中课表", state = rememberWindowState(width = 1180.dp, height = 820.dp)) {
            App(actions)
        }
    }
}

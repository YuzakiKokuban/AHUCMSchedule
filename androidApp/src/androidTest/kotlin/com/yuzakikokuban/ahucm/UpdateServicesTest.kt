package com.yuzakikokuban.ahucm

import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuzakikokuban.ahucm.update.UpdateState
import com.yuzakikokuban.ahucm.update.UpdateViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class UpdateServicesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aboutShowsVersionCopyrightAndTopLeftBackIcon() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        org.junit.Assume.assumeFalse("Unlock device to verify the about UI", keyguard.isKeyguardLocked)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            context.startActivity(android.content.Intent(context, com.yuzakikokuban.ahucm.update.AboutActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        fun containsText(node: android.view.accessibility.AccessibilityNodeInfo, value: String): Boolean {
            if (node.text?.contains(value) == true) return true
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; if (containsText(child, value)) return true }
            return false
        }
        var root: android.view.accessibility.AccessibilityNodeInfo? = null
        for (attempt in 0..100) {
            root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && containsText(root, "© 2026 YuzakiKokuban")) break
            Thread.sleep(100)
        }
        assertTrue(containsText(root!!, "© 2026 YuzakiKokuban"))
        assertTrue(containsText(root, "版本 ${BuildConfig.VERSION_NAME}"))
        fun findBack(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == "返回") return node
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; findBack(child)?.let { return it } }
            return null
        }
        val back = findBack(root)!!
        val bounds = android.graphics.Rect().also { back.getBoundsInScreen(it) }
        val screen = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        assertTrue(bounds.centerX() < screen.centerX())
        assertTrue(bounds.centerY() < screen.height() / 4)
    }

    @Test fun updateProviderUsesPrivateContentUriAndTemporaryGrants() {
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val sample = File.createTempFile("provider-test", ".apk", directory)
        try {
            sample.writeText("anonymous fixture")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", sample)
            assertEquals("content", uri.scheme)
            assertEquals("anonymous fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "private-account")) }.isFailure)
        } finally { sample.delete() }
    }

    @Test fun invalidCachedApkAllowsRedownloadAndCannotBeInstalled() {
        val app = context.applicationContext as android.app.Application
        val model = UpdateViewModel(app)
        model.state.value = UpdateState(ready = true)
        assertTrue(runCatching { runBlocking { model.verifiedApk() } }.isFailure)
        model.installationFailed()
        assertFalse(model.state.value.ready)
        assertTrue(model.state.value.message.contains("重新下载"))
    }
}

package cn.edu.ahtcm.schedule.desktop

import cn.edu.ahtcm.schedule.core.CredentialProtector
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/** DPAPI current-user scope; no machine-wide or plaintext fallback. */
class WindowsCredentialProtector : CredentialProtector {
    override fun encrypt(clear: ByteArray): ByteArray = Crypt32Util.cryptProtectData(clear, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
    override fun decrypt(sealed: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(sealed, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
}

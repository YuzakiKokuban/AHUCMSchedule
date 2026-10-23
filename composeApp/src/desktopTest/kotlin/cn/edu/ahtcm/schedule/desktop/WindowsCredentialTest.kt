package cn.edu.ahtcm.schedule.desktop
import cn.edu.ahtcm.schedule.core.*
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*
class WindowsCredentialTest {
    @Test fun `Windows DPAPI credential store round trips and detects tampering`() {
        if(!System.getProperty("os.name").startsWith("Windows")) return
        val folder=Files.createTempDirectory("ahu-dpapi")
        try {
            val protector=WindowsCredentialProtector()
            val store=EncryptedCredentialStore(folder.resolve("login.bin"),protector)
            val login=SavedLogin("anonymous","anonymous-secret")
            store.save(login)
            assertEquals(login,store.load())
            assertFalse(Files.readAllBytes(folder.resolve("login.bin")).toString(Charsets.UTF_8).contains(login.password))
            assertFails { protector.decrypt(byteArrayOf(0,1,2,3)) }
            store.clear(); assertNull(store.load())
        } finally { folder.toFile().deleteRecursively() }
    }
}

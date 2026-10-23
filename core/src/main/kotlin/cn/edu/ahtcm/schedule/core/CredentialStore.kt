package cn.edu.ahtcm.schedule.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable data class SavedLogin(val account: String, val password: String) {
    override fun toString() = "SavedLogin(redacted)"
}
@Serializable data class SchoolWebSession(val account: String, val cookies: Map<String, String>, val casAuthenticated: Boolean = false) {
    override fun toString() = "SchoolWebSession(redacted)"
}
interface CredentialStore {
    fun load(): SavedLogin?
    fun save(login: SavedLogin)
    fun clear()
}
interface CredentialProtector {
    fun encrypt(clear: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

class EncryptedCredentialStore(private val path: Path, private val protector: CredentialProtector) : CredentialStore {
    @Synchronized override fun load(): SavedLogin? {
        if (!Files.exists(path)) return null
        require(Files.size(path) in 1..65536)
        val clear = protector.decrypt(Files.readAllBytes(path))
        return try { Json.decodeFromString<SavedLogin>(clear.toString(Charsets.UTF_8)).also {
            require(it.account.isNotBlank() && it.password.isNotEmpty())
        } } finally { clear.fill(0) }
    }
    @Synchronized override fun save(login: SavedLogin) {
        require(login.account.isNotBlank() && login.password.isNotEmpty())
        val clear = Json.encodeToString(login).toByteArray(Charsets.UTF_8)
        val sealed = try { protector.encrypt(clear) } finally { clear.fill(0) }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".login-", ".tmp")
        try {
            Files.write(temporary, sealed)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized override fun clear() { Files.deleteIfExists(path) }
}

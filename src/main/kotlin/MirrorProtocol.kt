import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

object AppFiles {
    val root: File = run {
        val location = File(AppFiles::class.java.protectionDomain.codeSource.location.toURI())
        if (location.parentFile?.name == "app") location.parentFile.parentFile else File(System.getProperty("user.dir"))
    }
    fun binary(name: String): String = listOf(File(root, "bin/$name.exe"), File("../bin/$name.exe"))
        .firstOrNull { it.isFile }?.absolutePath ?: name
    fun writeAtomic(file: File, text: String) {
        file.absoluteFile.parentFile.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".save-", ".tmp")
        try {
            Files.writeString(temp, text)
            try { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temp) }
    }
}

data class PublicConfig(val baseUri: String, val password: String, val apiKey: String = "") {
    fun base(): URI {
        val uri = URI(baseUri.trim().trimEnd('/') + "/")
        val localTestServer = uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")
        require((uri.scheme == "https" || localTestServer) && uri.host != null && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "Geçerli bir HTTPS sunucu adresi girin." }
        return uri
    }
    fun archivePassword(): String = try {
        String(Base64.getDecoder().decode(password.filterNot { it.isWhitespace() }), UTF_8)
    } catch (_: IllegalArgumentException) { error("JSON içindeki arşiv parolası geçerli Base64 değil.") }
    fun key(): String = apiKey.ifBlank { System.getenv("QUEST_MIRROR_API_KEY").orEmpty() }.also {
        require(it.isNotBlank()) { "Sunucu API anahtarı gerekli. Bağlantı ayarlarına anahtarınızı girin." }
        require('\r' !in it && '\n' !in it) { "API anahtarı satır sonu içeremez." }
    }
}

data class RemotePackage(
    val folderName: String,
    val sizeInfo: String = "",
    val displayName: String = folderName,
    val packageName: String = "",
    val versionCode: String = ""
) {
    val remoteHash: String get() = MirrorProtocol.hash(folderName)
}

object MirrorProtocol {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    fun hash(releaseName: String): String = MessageDigest.getInstance("MD5")
        .digest((releaseName + "\n").toByteArray(UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun parseConfig(raw: String): PublicConfig {
        val obj = json.parseToJsonElement(raw).jsonObject
        fun value(name: String) = obj[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        return PublicConfig(value("baseUri"), value("password"), value("apiKey")).also {
            it.base(); it.archivePassword()
        }
    }
    fun parseCatalog(raw: String): List<RemotePackage> {
        val lines = raw.removePrefix("\uFEFF").lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty() && lines.first().contains(';')) { "Katalog başlığı geçersiz." }
        val entries = lines.drop(1).mapIndexed { index, line ->
            val cells = line.split(';')
            require(cells.size >= 3 && cells[1].isNotBlank()) { "Katalog satırı geçersiz: ${index + 2}" }
            RemotePackage(cells[1], cells.getOrElse(5) { "" }, cells[0], cells[2], cells.getOrElse(3) { "" })
        }
        require(entries.isNotEmpty()) { "Katalog boş; önceki katalog korunuyor." }
        return entries.distinctBy { it.folderName }
    }
    fun safeArchiveEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.isNotBlank() && !normalized.startsWith('/') && ':' !in normalized &&
            normalized.split('/').none { it == ".." } && '\u0000' !in normalized
    }
}

/** Windows DPAPI: the key can only be recovered by the same Windows account. */
object KeyStorage {
    fun protect(value: String): String = if (value.isBlank()) "" else transform(value, true)
    fun unprotect(value: String): String = if (value.isBlank()) "" else transform(value, false)
    private fun transform(value: String, protect: Boolean): String {
        val operation = if (protect) "Protect" else "Unprotect"
        val script = "Add-Type -AssemblyName System.Security; " +
            "\$b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); " +
            "\$r=[Security.Cryptography.ProtectedData]::$operation(\$b,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
            "[Console]::Out.Write([Convert]::ToBase64String(\$r))"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).start()
        val input = if (protect) Base64.getEncoder().encodeToString(value.toByteArray(UTF_8)) else value
        process.outputStream.bufferedWriter().use { it.write(input) }
        val result = process.inputStream.bufferedReader().readText().trim()
        val error = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0 && error.isBlank()) { "Windows anahtar kasası açılamadı. Anahtarı yeniden girin." }
        return if (protect) result else String(Base64.getDecoder().decode(result), UTF_8)
    }
}

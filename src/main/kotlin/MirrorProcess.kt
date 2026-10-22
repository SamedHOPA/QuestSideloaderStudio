import kotlinx.coroutines.*
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread

object MirrorProcess {
    fun redact(text: String, secrets: List<String>): String {
        var result = text
        secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "***") }
        return result.replace(Regex("(?i)(X-API-Key|Authorization)([=: ]+)[^\\r\\n]+"), "$1$2***")
    }
    suspend fun run(
        command: List<String>, environment: Map<String, String> = emptyMap(),
        secrets: List<String> = emptyList(), onLine: (String) -> Unit = {}, maxLines: Int = 1000
    ): String = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        process.outputStream.close()
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val reader = thread(isDaemon = true, name = "mirror-output") {
            try {
                process.inputStream.bufferedReader().useLines { sequence -> sequence.forEach {
                    val clean = redact(it, secrets)
                    synchronized(lines) { lines.add(clean); if (lines.size > maxLines) lines.removeAt(0) }
                    onLine(clean)
                } }
            } catch (_: Exception) { /* Closing a cancelled process also closes its pipe. */ }
        }
        try {
            while (process.isAlive) { ensureActive(); delay(100) }
            reader.join(3000)
            val output = synchronized(lines) { lines.joinToString("\n") }
            check(process.exitValue() == 0) { explain(output, process.exitValue()) }
            output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }
    fun explain(output: String, code: Int): String {
        val reason = when {
            "403" in output -> "Sunucu erişimi reddetti (403). API anahtarını ve sunucu erişim iznini kontrol edin."
            "401" in output -> "API anahtarı geçersiz veya süresi dolmuş (401)."
            "429" in output -> "Sunucu istek sınırına ulaşıldı (429). Daha sonra yeniden deneyin."
            else -> "İşlem başarısız (çıkış kodu $code)."
        }
        return "$reason\n${output.takeLast(5000)}"
    }
}

object ArchiveService {
    suspend fun extract(archive: File, destination: File, password: String, onLine: (String) -> Unit = {}) {
        require(!destination.exists() || destination.listFiles().orEmpty().isEmpty()) { "Arşiv hedefi boş olmalı." }
        val executable = AppFiles.binary("7z")
        // Validate the complete listing before extraction, including split archives.
        val listing = MirrorProcess.run(listOf(executable, "l", "-slt", "-ba", "-sccUTF-8", "-p$password", archive.absolutePath),
            secrets = listOf(password), maxLines = Int.MAX_VALUE)
        val entries = listing.lineSequence().filter { it.startsWith("Path = ") }.map { it.removePrefix("Path = ") }.toList()
        require(entries.isNotEmpty() && entries.all(MirrorProtocol::safeArchiveEntry)) { "Arşiv güvenli olmayan bir dosya yolu içeriyor." }
        require(!Regex("(?m)^(Symbolic Link|Hard Link) = .+").containsMatchIn(listing) &&
            !Regex("(?m)^Attributes = .*l[rwx-]{9}").containsMatchIn(listing)) { "Arşiv bağlantı içeren dosyalar barındırıyor." }
        destination.mkdirs()
        MirrorProcess.run(listOf(executable, "x", archive.absolutePath, "-o${destination.absolutePath}", "-p$password", "-y", "-sccUTF-8"),
            secrets = listOf(password), onLine = onLine)
    }
}

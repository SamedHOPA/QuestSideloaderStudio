import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RcloneController(
    private val rclonePath: String = AppFiles.binary("rclone"),
    private val configPath: String = File(AppFiles.root, "rclone.conf").absolutePath,
    private val configFile: File = File(AppFiles.root, "config.json")
) {
    var configWarning: String? = null
        private set
    private var activeConfig = PublicConfig("https://go.srcdl1.xyz/", "")
    fun resolveRcloneBinary(): String = rclonePath

    fun loadSavedConfig(): PublicConfig {
        if (!configFile.exists()) return activeConfig
        return try {
            val raw = configFile.readText()
            val parsed = MirrorProtocol.parseConfig(raw)
            val protectedKey = MirrorProtocol.json.parseToJsonElement(raw).jsonObject["apiKeyProtected"]?.jsonPrimitive?.content.orEmpty()
            val key = try { KeyStorage.unprotect(protectedKey).ifBlank { parsed.apiKey } }
            catch (e: Exception) { configWarning = e.message; "" }
            parsed.copy(apiKey = key).also { activeConfig = it }
        } catch (e: Exception) {
            configWarning = "Bağlantı ayarları okunamadı: ${e.message}"
            activeConfig
        }
    }

    fun saveConfig(config: PublicConfig) {
        config.base(); config.archivePassword()
        val protectedKey = KeyStorage.protect(config.apiKey)
        val json = buildJsonObject {
            put("baseUri", config.base().toString()); put("password", config.password)
            put("apiKeyProtected", protectedKey)
        }
        AppFiles.writeAtomic(configFile, MirrorProtocol.json.encodeToString(JsonObject.serializer(), json))
        activeConfig = config.copy(baseUri = config.base().toString())
    }

    fun parsePublicConfig(rawText: String): PublicConfig? = try { MirrorProtocol.parseConfig(rawText) } catch (_: Exception) { null }
    fun archivePassword(): String = activeConfig.archivePassword()
    private fun cacheRoot(config: PublicConfig): File = File(configFile.absoluteFile.parentFile, "cache/mirror-${MirrorProtocol.hash(config.base().toString())}")
    fun loadCachedPackages(): List<RemotePackage> = runCatching {
        MirrorProtocol.parseCatalog(File(cacheRoot(activeConfig), "catalog.txt").readText())
    }.getOrDefault(emptyList())

    fun getThumbnailFile(packageName: String): File? {
        if (packageName.isBlank()) return null
        return try {
            val cache = cacheRoot(activeConfig)
            val snapshotName = File(cache, "snapshot.txt").readTextOrNull()?.trim() ?: return null
            val file = File(cache, "$snapshotName/.meta/thumbnails/$packageName.jpg")
            if (file.isFile) file else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun listRemotePackages(): Result<List<RemotePackage>> = withContext(Dispatchers.IO) {
        try {
            val config = activeConfig
            val key = config.key()
            val cache = cacheRoot(config).apply { mkdirs() }
            val staging = Files.createTempDirectory(cache.toPath(), "refresh-").toFile()
            try {
                val archive = File(staging, "meta.7z")
                MirrorProcess.run(listOf(rclonePath, "copyurl", config.base().resolve("meta.7z").toString(), archive.absolutePath) + commonArgs(),
                    environment(key), listOf(key, config.password, config.archivePassword()))
                val signature = archive.inputStream().use { it.readNBytes(6) }
                require(signature.contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c))) {
                    "Sunucu geçerli bir 7z kataloğu döndürmedi. HTML hata yanıtı alınmış olabilir."
                }
                val extracted = File(staging, "meta")
                ArchiveService.extract(archive, extracted, config.archivePassword())
                val catalog = extracted.listFiles().orEmpty().firstOrNull { it.isFile && it.name.endsWith("ameList.txt", true) }
                    ?: error("Metadata arşivinde katalog bulunamadı.")
                val raw = catalog.readText()
                val entries = MirrorProtocol.parseCatalog(raw)
                // Publish only a fully downloaded, decrypted and parsed snapshot.
                val snapshot = File(cache, "snapshot-${System.currentTimeMillis()}")
                if (!extracted.renameTo(snapshot)) {
                    snapshot.mkdirs()
                    extracted.copyRecursively(snapshot, overwrite = true)
                }
                AppFiles.writeAtomic(File(cache, "catalog.txt"), raw)
                AppFiles.writeAtomic(File(cache, "snapshot.txt"), snapshot.name)
                Result.success(entries)
            } finally { staging.deleteRecursively() }
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun downloadPackage(
        packageFolder: String, localDestDir: File,
        onProgressLine: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val config = activeConfig
            val key = config.key()
            val hash = MirrorProtocol.hash(packageFolder)
            val source = MirrorProtocol.hash(config.base().toString())
            val directory = File(localDestDir, "$source/$hash").apply { mkdirs() }
            val content = File(directory, "content")
            if (File(directory, "complete.txt").readTextOrNull() == packageFolder && content.isDirectory)
                return@withContext Result.success(content)
            val parts = File(directory, "parts").apply { mkdirs() }
            MirrorProxy.start(rclonePath, config.base().resolve("$hash/").toString(), environment(key), commonArgs(), key).use { proxy ->
                try { ResumableDownload.download(proxy.url, parts, onProgressLine) }
                catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { throw proxy.failure(e) }
            }
            onProgressLine("İndirme tamamlandı; arşiv doğrulanıyor ve açılıyor…")
            val archive = File(parts, "$hash.7z.001").takeIf { it.isFile }
                ?: File(parts, "$hash.7z").takeIf { it.isFile }
                ?: error("Paketin ilk arşiv parçası bulunamadı: $hash.7z.001")
            val staging = Files.createTempDirectory(directory.toPath(), "extract-").toFile()
            try {
                ArchiveService.extract(archive, staging, config.archivePassword())
                require(staging.walkTopDown().any { it.isFile && it.extension.equals("apk", true) }) { "Arşivde APK bulunamadı." }
                if (content.exists()) content.deleteRecursively()
                if (!staging.renameTo(content)) {
                    content.mkdirs()
                    staging.copyRecursively(content, overwrite = true)
                }
                AppFiles.writeAtomic(File(directory, "complete.txt"), packageFolder)
            } finally { staging.deleteRecursively() }
            Result.success(content)
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { Result.failure(e) }
    }

    companion object {
        fun commonArgs() = listOf("--tpslimit", "1", "--tpslimit-burst", "3", "--contimeout", "10s", "--timeout", "60s", "--retries", "1", "--low-level-retries", "1")
        fun environment(key: String) = mapOf("RCLONE_HEADER" to "X-API-Key: $key")
    }
}

private fun File.readTextOrNull(): String? = if (isFile) readText() else null

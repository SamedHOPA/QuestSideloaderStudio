import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dongliu.apk.parser.ApkFile
import java.io.File

data class ApkInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val file: File
)

class ApkInstallerService(private val adbController: AdbController) {

    private fun resolve7zBinary(): String? {
        val candidates = listOf(
            File("bin/7z.exe"),
            File("bin/7za.exe"),
            File("C:\\Program Files\\7-Zip\\7z.exe"),
            File("C:\\Program Files (x86)\\7-Zip\\7z.exe")
        )
        val existing = candidates.find { it.exists() }
        if (existing != null) return existing.absolutePath

        return try {
            val p = ProcessBuilder("7z", "-h").redirectErrorStream(true).start()
            p.waitFor()
            "7z"
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractArchivesIfPresent(
        directory: File,
        password: String,
        onLog: (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val archives = directory.walkTopDown().filter {
            it.isFile && (it.extension.equals("7z", ignoreCase = true) || it.extension.equals("zip", ignoreCase = true))
        }.toList()

        if (archives.isEmpty()) return@withContext Result.success(Unit)

        val sevenZipBin = resolve7zBinary()
        if (sevenZipBin == null) {
            onLog("⚠️ Klasörde sıkıştırılmış arşiv (.7z/.zip) bulundu ancak 7z.exe algılanamadı.")
            onLog("💡 Şifreli arşivi otomatik açmak için 'bin/7z.exe' dosyasını ekleyin.")
            return@withContext Result.failure(Exception("7z.exe bulunamadığı için şifreli arşiv açılamadı."))
        }

        for (archive in archives) {
            onLog("📂 Arşiv ayıklanıyor: ${archive.name}...")
            val outDir = archive.parentFile
            val cmd = mutableListOf(sevenZipBin, "x", archive.absolutePath, "-o${outDir.absolutePath}", "-y")
            if (password.isNotBlank()) {
                cmd.add("-p$password")
            }

            try {
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()

                if (exitCode == 0) {
                    onLog("✅ Arşiv başarıyla ayıklandı: ${archive.name}")
                    archive.delete()
                } else {
                    return@withContext Result.failure(Exception("Arşiv açma hatası (${archive.name}): $output"))
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
        Result.success(Unit)
    }

    fun parseApk(apkFile: File): ApkInfo? {
        return try {
            ApkFile(apkFile).use { apk ->
                val meta = apk.apkMeta
                ApkInfo(
                    packageName = meta.packageName,
                    appName = meta.label ?: apkFile.nameWithoutExtension,
                    versionName = meta.versionName ?: "1.0",
                    versionCode = meta.versionCode,
                    file = apkFile
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun sideloadDirectoryOrApk(
        target: File,
        archivePassword: String = "",
        onLog: (String) -> Unit
    ): Result<Unit> {
        val workDir = if (target.isDirectory) target else target.parentFile

        val extractRes = extractArchivesIfPresent(workDir, archivePassword, onLog)
        if (extractRes.isFailure) {
            return Result.failure(extractRes.exceptionOrNull()!!)
        }

        val apkFiles = if (target.isDirectory) {
            target.walkTopDown().filter { it.extension.equals("apk", ignoreCase = true) }.toList()
        } else {
            listOf(target)
        }

        if (apkFiles.isEmpty()) {
            return Result.failure(Exception("Seçilen konumda APK dosyası bulunamadı!"))
        }

        val mainApk = apkFiles.first()
        val apkInfo = parseApk(mainApk)
            ?: return Result.failure(Exception("APK Manifest okunamadı: ${mainApk.name}"))

        onLog("📦 Paket Algılandı: ${apkInfo.appName} (${apkInfo.packageName} - v${apkInfo.versionName})")

        val installResult = adbController.installApk(mainApk, onLog)
        if (installResult.isFailure) return Result.failure(installResult.exceptionOrNull()!!)
        onLog("✅ APK başarıyla kuruldu.")

        val obbFiles = workDir.walkTopDown()
            .filter { it.extension.equals("obb", ignoreCase = true) }
            .toList()

        if (obbFiles.isNotEmpty()) {
            onLog("📂 ${obbFiles.size} adet OBB veri dosyası bulundu. Cihaza aktarılıyor...")
            val remoteObbDir = "/sdcard/Android/obb/${apkInfo.packageName}/"
            adbController.createRemoteDir(remoteObbDir)

            for (obb in obbFiles) {
                onLog("⏳ Aktarılıyor: ${obb.name} (${obb.length() / (1024 * 1024)} MB)...")
                val pushRes = adbController.pushFile(obb, "$remoteObbDir${obb.name}")
                if (pushRes.isFailure) {
                    return Result.failure(Exception("OBB aktarım hatası (${obb.name}): ${pushRes.exceptionOrNull()?.message}"))
                }
            }
            onLog("✅ Tüm OBB dosyaları başarıyla $remoteObbDir dizinine kopyalandı!")
        } else {
            onLog("ℹ️ Ek OBB dosyası bulunmadı, işlem tamamlandı.")
        }

        return Result.success(Unit)
    }
}

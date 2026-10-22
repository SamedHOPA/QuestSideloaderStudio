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

    suspend fun sideloadDirectoryOrApk(target: File, onLog: (String) -> Unit): Result<Unit> {
        val workDir = if (target.isDirectory) target else target.parentFile
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

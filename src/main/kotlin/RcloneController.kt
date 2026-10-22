import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RemotePackage(val folderName: String, val sizeInfo: String = "")

class RcloneController(
    private val rclonePath: String = "rclone",
    private val configPath: String = "rclone.conf"
) {

    private fun resolveRcloneBinary(): String =
        if (File("bin/rclone.exe").exists()) "bin/rclone.exe" else rclonePath

    suspend fun listRemotePackages(remoteName: String, remotePath: String = ""): Result<List<RemotePackage>> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                resolveRcloneBinary(), "lsf", "$remoteName:$remotePath",
                "--config", configPath,
                "--dirs-only"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) {
                val packages = output.lines()
                    .filter { it.isNotBlank() }
                    .map { RemotePackage(it.removeSuffix("/")) }
                Result.success(packages)
            } else {
                Result.failure(Exception("Rclone listeleme hatası: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadPackage(
        remoteName: String,
        packageFolder: String,
        localDestDir: File,
        onProgressLine: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val targetDir = File(localDestDir, packageFolder).apply { mkdirs() }
            val process = ProcessBuilder(
                resolveRcloneBinary(), "copy",
                "$remoteName:$packageFolder",
                targetDir.absolutePath,
                "--config", configPath,
                "--progress",
                "--stats", "1s",
                "--transfers", "4"
            ).redirectErrorStream(true).start()

            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("Transferred:") || line.contains("%")) {
                        onProgressLine(line.trim())
                    }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Result.success(targetDir)
            } else {
                Result.failure(Exception("İndirme başarısız oldu (Kod: $exitCode)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

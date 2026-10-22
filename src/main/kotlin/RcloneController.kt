import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RemotePackage(val folderName: String, val sizeInfo: String = "")

data class PublicConfig(val baseUri: String, val password: String)

class RcloneController(
    private val rclonePath: String = "rclone",
    private val configPath: String = "rclone.conf",
    private val configFile: File = File("config.json")
) {

    fun resolveRcloneBinary(): String {
        val appRoot = try {
            File(RcloneController::class.java.protectionDomain.codeSource.location.toURI()).parentFile?.parentFile
        } catch (e: Exception) {
            null
        }

        val candidates = listOfNotNull(
            File("bin/rclone.exe"),
            File("../bin/rclone.exe"),
            if (appRoot != null) File(appRoot, "bin/rclone.exe") else null,
            File(System.getProperty("user.dir"), "bin/rclone.exe")
        )

        val found = candidates.find { it.exists() }
        return found?.absolutePath ?: rclonePath
    }

    fun loadSavedConfig(): PublicConfig {
        return try {
            if (configFile.exists()) {
                val content = configFile.readText()
                parsePublicConfig(content) ?: PublicConfig("https://go.srcdl1.xyz/", "Z0w1OVZmZ1B4b0hS")
            } else {
                PublicConfig("https://go.srcdl1.xyz/", "Z0w1OVZmZ1B4b0hS")
            }
        } catch (e: Exception) {
            PublicConfig("https://go.srcdl1.xyz/", "Z0w1OVZmZ1B4b0hS")
        }
    }

    fun saveConfig(config: PublicConfig) {
        try {
            val json = """
{
  "baseUri": "${config.baseUri.trim()}",
  "password": "${config.password.trim()}"
}
            """.trimIndent()
            configFile.writeText(json)
            updateRcloneConfigFile(config.baseUri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateRcloneConfigFile(baseUri: String) {
        val confFile = File(configPath)
        val url = if (baseUri.endsWith("/")) baseUri.trim() else "${baseUri.trim()}/"
        val content = """
[quest-mirror]
type = http
url = $url
        """.trimIndent()
        confFile.writeText(content)
    }

    fun parsePublicConfig(rawText: String): PublicConfig? {
        val uriRegex = """"baseUri"\s*:\s*"([^"]+)"""".toRegex()
        val passRegex = """"password"\s*:\s*"([^"]+)"""".toRegex()

        val uriMatch = uriRegex.find(rawText)?.groupValues?.get(1)
        val passMatch = passRegex.find(rawText)?.groupValues?.get(1)

        return if (uriMatch != null) {
            PublicConfig(uriMatch, passMatch ?: "")
        } else null
    }

    suspend fun listRemotePackages(remoteName: String = "quest-mirror", remotePath: String = ""): Result<List<RemotePackage>> = withContext(Dispatchers.IO) {
        try {
            val rcloneBin = resolveRcloneBinary()
            val conf = File(configPath)
            if (!conf.exists()) {
                val saved = loadSavedConfig()
                updateRcloneConfigFile(saved.baseUri)
            }

            val process = ProcessBuilder(
                rcloneBin, "lsf", "$remoteName:$remotePath",
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
        remoteName: String = "quest-mirror",
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
                    if (line.contains("Transferred:") || line.contains("%") || line.contains("ETA")) {
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

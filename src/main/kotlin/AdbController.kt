import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ConnectedDevice(val serial: String, val model: String, val state: String)

class AdbController(private val adbPath: String = "adb") {

    fun resolveAdbBinary(): String {
        val appRoot = try {
            File(AdbController::class.java.protectionDomain.codeSource.location.toURI()).parentFile?.parentFile
        } catch (e: Exception) {
            null
        }

        val candidates = listOfNotNull(
            File("bin/adb.exe"),
            File("../bin/adb.exe"),
            if (appRoot != null) File(appRoot, "bin/adb.exe") else null,
            File(System.getProperty("user.dir"), "bin/adb.exe"),
            File(System.getenv("LOCALAPPDATA") ?: "", "Android/Sdk/platform-tools/adb.exe")
        )

        val found = candidates.find { it.exists() }
        return found?.absolutePath ?: adbPath
    }

    private suspend fun runCommand(vararg args: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val resolvedAdb = resolveAdbBinary()
            val command = listOf(resolvedAdb) + args
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Result.success(output.trim())
            } else {
                Result.failure(Exception("Hata Kodu $exitCode: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getConnectedDevices(): List<ConnectedDevice> {
        val result = runCommand("devices", "-l").getOrNull() ?: return emptyList()
        val lines = result.lines()
        val headerIndex = lines.indexOfFirst { it.trim().startsWith("List of devices attached") }
        val deviceLines = if (headerIndex != -1) lines.drop(headerIndex + 1) else lines

        return deviceLines
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val serial = parts[0]
                    val state = parts[1]
                    val model = parts.find { it.startsWith("model:") }
                        ?.substringAfter("model:")
                        ?.replace("_", " ") ?: "Android/Quest"
                    ConnectedDevice(serial, model, state)
                } else null
            }
    }

    suspend fun installApk(apkFile: File, onProgress: (String) -> Unit): Result<String> {
        onProgress("${apkFile.name} yükleniyor...")
        return runCommand("install", "-r", "-g", apkFile.absolutePath)
    }

    suspend fun createRemoteDir(remotePath: String) = runCommand("shell", "mkdir", "-p", remotePath)

    suspend fun pushFile(localFile: File, remotePath: String) = runCommand("push", localFile.absolutePath, remotePath)

    suspend fun getInstalledPackages(): List<String> {
        val output = runCommand("shell", "pm", "list", "packages", "-3").getOrNull() ?: return emptyList()
        return output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .sorted()
    }

    suspend fun uninstallPackage(packageName: String): Result<String> = runCommand("uninstall", packageName)

    suspend fun enableWirelessAdb(): Result<String> {
        val ipOutput = runCommand("shell", "ip", "route").getOrNull() ?: return Result.failure(Exception("IP alınamadı"))
        val deviceIp = ipOutput.substringAfter("src ").substringBefore(" ").trim()
        runCommand("tcpip", "5555")
        return runCommand("connect", "$deviceIp:5555")
    }

    suspend fun setQuestRefreshRate(hz: Int): Result<String> =
        runCommand("shell", "setprop", "debug.oculus.refreshRate", hz.toString())

    suspend fun setQuestTextureResolution(width: Int, height: Int): Result<String> {
        runCommand("shell", "setprop", "debug.oculus.textureWidth", width.toString())
        return runCommand("shell", "setprop", "debug.oculus.textureHeight", height.toString())
    }

    suspend fun setProximitySensor(enabled: Boolean): Result<String> {
        val cmd = if (enabled) "enable" else "disable"
        return runCommand("shell", "am", "broadcast", "-a", "com.oculus.vrpowermanager.automation_$cmd")
    }

    suspend fun backupAppData(packageName: String, backupBaseDir: File): Result<File> {
        val destDir = File(backupBaseDir, "$packageName-backup").apply { mkdirs() }
        val res = runCommand("pull", "/sdcard/Android/data/$packageName", destDir.absolutePath)
        return if (res.isSuccess) Result.success(destDir) else Result.failure(res.exceptionOrNull()!!)
    }
}

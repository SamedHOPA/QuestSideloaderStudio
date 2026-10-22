import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalTime
import javax.swing.JFileChooser

object ThumbnailCache {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    fun get(file: File?): ImageBitmap? {
        if (file == null || !file.exists()) return null
        val cached = memory[file.absolutePath]
        if (cached != null) return cached
        return try {
            file.inputStream().use { stream ->
                val bmp = androidx.compose.ui.res.loadImageBitmap(stream)
                memory[file.absolutePath] = bmp
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }
}

fun formatSize(sizeMb: String): String {
    val mb = sizeMb.toDoubleOrNull() ?: return if (sizeMb.isNotBlank()) "$sizeMb MB" else ""
    return if (mb >= 1024) {
        String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        "${mb.toInt()} MB"
    }
}

enum class SortOption(val title: String) {
    POPULARITY_DESC("🔥 En Popüler"),
    NAME_ASC("🔤 İsim (A-Z)"),
    NAME_DESC("🔤 İsim (Z-A)"),
    SIZE_DESC("📦 Boyut (En Büyük)"),
    SIZE_ASC("📦 Boyut (En Küçük)"),
    RECENT_DESC("🕒 Son Güncellenen")
}

data class ActiveDownload(
    val pkg: RemotePackage,
    val installAfter: Boolean,
    val progressFraction: Float? = null,
    val statusText: String = "Başlatılıyor...",
    val isInstalling: Boolean = false
)

private val DarkThemeColors = darkColorScheme(
    primary = Color(0xFF6366F1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF10B981),
    onSecondary = Color.White,
    background = Color(0xFF0E0E14),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF16161F),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF22222E),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF2E2E3E),
    error = Color(0xFFEF4444)
)

@Composable
fun SideloaderApp(
    adbController: AdbController,
    rcloneController: RcloneController,
    installerService: ApkInstallerService
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(1) } // Default directly to Cloud Library for best user flow
    var devices by remember { mutableStateOf<List<ConnectedDevice>>(emptyList()) }
    var installedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var remotePackages by remember { mutableStateOf(rcloneController.loadCachedPackages()) }
    var searchQuery by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf("Sistem hazır. Meta Quest veya Android cihazınızı bağlayın.")) }
    var isWorking by remember { mutableStateOf(false) }
    var deleteAfterInstall by remember { mutableStateOf(true) }

    // Server Config
    val initialConfig = remember { rcloneController.loadSavedConfig() }
    var mirrorUrl by remember { mutableStateOf(initialConfig.baseUri) }
    var mirrorPassword by remember { mutableStateOf(initialConfig.password) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showLogsDrawer by remember { mutableStateOf(false) }

    // Download & Install State
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var pausedPackage by remember { mutableStateOf<RemotePackage?>(null) }
    var installAfterResume by remember { mutableStateOf(false) }
    var activeDownload by remember { mutableStateOf<ActiveDownload?>(null) }

    val tempDownloadDir = remember { File(AppFiles.root, "downloads").apply { mkdirs() } }
    val backupBaseDir = remember { File(AppFiles.root, "backups").apply { mkdirs() } }

    fun appendLog(msg: String) {
        val time = LocalTime.now().withNano(0)
        scope.launch { logs = (logs + "[$time] $msg").takeLast(200) }
    }

    fun refreshDeviceData() {
        scope.launch {
            isWorking = true
            devices = adbController.getConnectedDevices()
            if (devices.isNotEmpty()) {
                val dev = devices.first()
                appendLog("✅ Cihaz bağlandı: ${dev.model} (${dev.serial})")
                installedPackages = adbController.getInstalledPackages()
            } else {
                appendLog("⚠️ Bağlı cihaz bulunamadı. USB kablosunu ve USB Hata Ayıklama iznini kontrol edin.")
                installedPackages = emptyList()
            }
            isWorking = false
        }
    }

    fun fetchCatalog(url: String = mirrorUrl, pass: String = mirrorPassword) {
        scope.launch {
            isWorking = true
            try {
                withContext(Dispatchers.IO) {
                    rcloneController.saveConfig(PublicConfig(url, pass, EMBEDDED_API_KEY))
                }
                pausedPackage = null
                activeDownload = null
                remotePackages = withContext(Dispatchers.IO) { rcloneController.loadCachedPackages() }
                appendLog("☁️ Sunucu kataloğu indiriliyor ve senkronize ediliyor...")
                remotePackages = rcloneController.listRemotePackages().getOrThrow()
                appendLog("✅ ${remotePackages.size} paket başarıyla listelendi.")
            } catch (e: Exception) {
                appendLog("❌ Katalog güncelleme hatası: ${e.message}")
            } finally {
                isWorking = false
            }
        }
    }

    fun download(pkg: RemotePackage, install: Boolean) {
        downloadJob = scope.launch {
            isWorking = true
            downloading = true
            pausedPackage = pkg
            installAfterResume = install
            activeDownload = ActiveDownload(
                pkg = pkg,
                installAfter = install,
                progressFraction = null,
                statusText = "Sunucuya bağlanılıyor..."
            )

            try {
                appendLog("⬇️ '${pkg.displayName}' indirmesi başlatıldı.")
                val downloaded = rcloneController.downloadPackage(
                    packageFolder = pkg.folderName,
                    localDestDir = tempDownloadDir,
                    onProgress = { fraction, status ->
                        activeDownload = activeDownload?.copy(progressFraction = fraction, statusText = status)
                        if (fraction == null || fraction == 1f) appendLog(status)
                    }
                ).getOrThrow()

                downloading = false
                pausedPackage = null

                if (install) {
                    activeDownload = activeDownload?.copy(
                        progressFraction = null,
                        statusText = "Cihaza kuruluyor (APK & OBB)...",
                        isInstalling = true
                    )
                    appendLog("📦 '${pkg.displayName}' cihaza kuruluyor…")
                    installerService.sideloadDirectoryOrApk(downloaded, rcloneController.archivePassword()) { logLine ->
                        appendLog(logLine)
                        activeDownload = activeDownload?.copy(statusText = logLine)
                    }.getOrThrow()

                    appendLog("🎉 '${pkg.displayName}' başarıyla kuruldu!")
                    activeDownload = activeDownload?.copy(
                        progressFraction = 1f,
                        statusText = "Kurulum Başarılı! 🎉",
                        isInstalling = false
                    )
                    if (deleteAfterInstall) {
                        downloaded.parentFile.deleteRecursively()
                        appendLog("🧹 İndirme dosyaları temizlendi.")
                    }
                    refreshDeviceData()
                } else {
                    activeDownload = activeDownload?.copy(
                        progressFraction = 1f,
                        statusText = "İndirme Tamamlandı! ✔️"
                    )
                    appendLog("✔️ İndirme tamamlandı: ${downloaded.absolutePath}")
                }
            } catch (cancel: CancellationException) {
                appendLog("⏸️ '${pkg.displayName}' indirmesi duraklatıldı.")
                activeDownload = null
            } catch (e: Exception) {
                appendLog("❌ Hata: ${e.message}")
                activeDownload = activeDownload?.copy(statusText = "Hata: ${e.message}")
            } finally {
                downloading = false
                isWorking = false
                if (activeDownload?.progressFraction == 1f) {
                    delay(3500)
                    if (!downloading) activeDownload = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDeviceData()
        if (remotePackages.isNotEmpty()) {
            appendLog("📚 Önbellekten ${remotePackages.size} adet oyun kütüphaneye yüklendi.")
        } else if (mirrorUrl.isBlank() || mirrorPassword.isBlank()) {
            // First time launch: guide the user with settings dialog
            showSettingsDialog = true
        }
    }

    MaterialTheme(colorScheme = DarkThemeColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {

                // 1. Top Header Bar
                AppHeader(
                    devices = devices,
                    isWorking = isWorking,
                    onRefreshDevice = ::refreshDeviceData,
                    onEnableWifiAdb = {
                        scope.launch {
                            isWorking = true
                            appendLog("📡 Kablosuz ADB (Wi-Fi) başlatılıyor...")
                            val res = adbController.enableWirelessAdb()
                            appendLog(res.getOrElse { "Hata: ${it.message}" })
                            refreshDeviceData()
                            isWorking = false
                        }
                    },
                    onOpenSettings = { showSettingsDialog = true }
                )

                Spacer(Modifier.height(10.dp))

                // 2. Navigation Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("📦 Yerel Kurulum & Yüklü (${installedPackages.size})", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("☁️ Bulut Kütüphanesi (${remotePackages.size})", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("⚙️ Quest Donanım & İnce Ayarlar", fontWeight = FontWeight.SemiBold) }
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 3. Tab Contents
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> LocalSideloadTab(
                            installedPackages = installedPackages,
                            isWorking = isWorking,
                            hasDevice = devices.isNotEmpty(),
                            onSelectTarget = { targetFile ->
                                scope.launch {
                                    isWorking = true
                                    val res = installerService.sideloadDirectoryOrApk(targetFile, mirrorPassword) { appendLog(it) }
                                    if (res.isFailure) appendLog("❌ Hata: ${res.exceptionOrNull()?.message}")
                                    refreshDeviceData()
                                    isWorking = false
                                }
                            },
                            onBackupPackage = { pkg ->
                                scope.launch {
                                    isWorking = true
                                    appendLog("💾 '$pkg' verileri yedekleniyor...")
                                    val res = adbController.backupAppData(pkg, backupBaseDir)
                                    res.onSuccess { appendLog("✅ Yedeklendi: ${it.absolutePath}") }
                                        .onFailure { appendLog("❌ Yedekleme hatası: ${it.message}") }
                                    isWorking = false
                                }
                            },
                            onUninstallPackage = { pkg ->
                                scope.launch {
                                    isWorking = true
                                    appendLog("🗑️ '$pkg' kaldırılıyor...")
                                    adbController.uninstallPackage(pkg)
                                    appendLog("✅ '$pkg' kaldırıldı.")
                                    refreshDeviceData()
                                    isWorking = false
                                }
                            }
                        )

                        1 -> CloudLibraryTab(
                            remotePackages = remotePackages,
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            deleteAfterInstall = deleteAfterInstall,
                            onToggleDelete = { deleteAfterInstall = it },
                            isWorking = isWorking,
                            hasDevice = devices.isNotEmpty(),
                            onRefreshCatalog = { fetchCatalog() },
                            onOpenSettings = { showSettingsDialog = true },
                            onDownloadOnly = { download(it, false) },
                            onDownloadAndInstall = { download(it, true) },
                            getThumbnailFile = rcloneController::getThumbnailFile
                        )

                        2 -> QuestTweaksTab(
                            isWorking = isWorking,
                            hasDevice = devices.isNotEmpty(),
                            onSetHz = { hz ->
                                scope.launch {
                                    adbController.setQuestRefreshRate(hz)
                                    appendLog("⚡ Quest ekran yenileme hızı $hz Hz olarak ayarlandı.")
                                }
                            },
                            onSetResolution = { w, h ->
                                scope.launch {
                                    adbController.setQuestTextureResolution(w, h)
                                    appendLog("🎯 Doku (Texture) çözünürlüğü ${w}x${h} olarak ayarlandı.")
                                }
                            },
                            onSetProximity = { enabled ->
                                scope.launch {
                                    adbController.setProximitySensor(enabled)
                                    val stateStr = if (enabled) "Açıldı" else "Kapatıldı"
                                    appendLog("👁️ Yakınlık sensörü (Proximity): $stateStr")
                                }
                            }
                        )
                    }
                }

                // 4. Active Download Floating Dock (with Progress Bar)
                if (activeDownload != null) {
                    Spacer(Modifier.height(8.dp))
                    DownloadDock(
                        activeDownload = activeDownload!!,
                        downloading = downloading,
                        onPause = { downloadJob?.cancel() },
                        onResume = {
                            if (pausedPackage != null && !isWorking) {
                                download(pausedPackage!!, installAfterResume)
                            }
                        },
                        onToggleLogs = { showLogsDrawer = !showLogsDrawer },
                        isLogsVisible = showLogsDrawer
                    )
                }

                Spacer(Modifier.height(6.dp))

                // 5. Minimalist Collapsible Console
                ModernConsoleBar(
                    logs = logs,
                    isExpanded = showLogsDrawer,
                    onToggle = { showLogsDrawer = !showLogsDrawer },
                    onClear = { logs = listOf("Log temizlendi.") }
                )
            }

            // 6. Settings Modal Dialog
            if (showSettingsDialog) {
                ServerSettingsDialog(
                    initialUrl = mirrorUrl,
                    initialPassword = mirrorPassword,
                    onDismiss = { showSettingsDialog = false },
                    onSaveAndFetch = { url, pass ->
                        mirrorUrl = url
                        mirrorPassword = pass
                        fetchCatalog(url, pass)
                    },
                    onParseJson = { rawJson ->
                        rcloneController.parsePublicConfig(rawJson)
                    }
                )
            }
        }
    }
}

@Composable
fun AppHeader(
    devices: List<ConnectedDevice>,
    isWorking: Boolean,
    onRefreshDevice: () -> Unit,
    onEnableWifiAdb: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Logo & Brand
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFF6366F1).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🥽", style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Quest Sideloader Studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF6366F1).copy(alpha = 0.15f)
                        ) {
                            Text("v1.1.0", style = MaterialTheme.typography.labelSmall, color = Color(0xFF818CF8), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    val activeDevice = devices.firstOrNull()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (activeDevice != null) Color(0xFF10B981) else Color(0xFFEF4444))
                        )
                        Text(
                            text = if (activeDevice != null) "${activeDevice.model} (${activeDevice.serial})" else "Cihaz Bağlı Değil (USB Hata Ayıklama Bekleniyor)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (activeDevice != null) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }
                }
            }

            // Quick Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRefreshDevice,
                    enabled = !isWorking,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("🔄 Cihazı Yenile", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onEnableWifiAdb,
                    enabled = devices.isNotEmpty() && !isWorking,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("📡 Wi-Fi ADB", style = MaterialTheme.typography.labelMedium)
                }

                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("⚙️ Sunucu Ayarları", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun DownloadDock(
    activeDownload: ActiveDownload,
    downloading: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onToggleLogs: () -> Unit,
    isLogsVisible: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A26)),
        border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF6366F1).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (activeDownload.isInstalling) "📦" else "⬇️", style = MaterialTheme.typography.titleMedium)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeDownload.pkg.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activeDownload.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA1A1AA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (downloading) {
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text("⏸️ Duraklat", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (activeDownload.progressFraction != 1f && !activeDownload.isInstalling) {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text("▶️ Devam Et", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    TextButton(
                        onClick = onToggleLogs,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(if (isLogsVisible) "Log Gizle ▼" else "Log Göster ▲", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Animated Progress Bar
            if (activeDownload.progressFraction != null) {
                LinearProgressIndicator(
                    progress = { activeDownload.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF6366F1),
                    trackColor = Color(0xFF282838)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF6366F1),
                    trackColor = Color(0xFF282838)
                )
            }
        }
    }
}

@Composable
fun ModernConsoleBar(
    logs: List<String>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111117)),
        border = BorderStroke(1.dp, Color(0xFF242432))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚡", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = logs.lastOrNull() ?: "Sistem hazır.",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9E9EA8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isExpanded) {
                        TextButton(
                            onClick = onClear,
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Temizle", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }

                    TextButton(
                        onClick = onToggle,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (isExpanded) "Logları Gizle ▼" else "Loglar (${logs.size}) ▲",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF818CF8)
                        )
                    }
                }
            }

            if (isExpanded) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF242432)))
                val listState = rememberLazyListState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color(0xFF09090D))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { log ->
                        val color = when {
                            log.contains("❌") || log.contains("Hata", ignoreCase = true) -> Color(0xFFF87171)
                            log.contains("✅") || log.contains("başarıyla", ignoreCase = true) -> Color(0xFF34D399)
                            log.contains("⚠️") -> Color(0xFFFBBF24)
                            log.contains("⬇️") || log.contains("📦") -> Color(0xFF60A5FA)
                            else -> Color(0xFFB0BEC5)
                        }
                        Text(
                            text = log,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ServerSettingsDialog(
    initialUrl: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSaveAndFetch: (url: String, pass: String) -> Unit,
    onParseJson: (String) -> PublicConfig?
) {
    var url by remember { mutableStateOf(initialUrl) }
    var password by remember { mutableStateOf(initialPassword) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showJsonInput by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("⚙️ Sunucu ve Bağlantı Ayarları", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(min = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Bulut oyun kataloğuna ve güncellemelere erişmek için sunucu adresi ve arşiv şifresini girin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                // JSON Quick Paste Box
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📋 Hızlı Yapılandırma (JSON)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            TextButton(
                                onClick = { showJsonInput = !showJsonInput },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(if (showJsonInput) "Kapat" else "JSON Bloğu Yapıştır", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (showJsonInput) {
                            OutlinedTextField(
                                value = jsonText,
                                onValueChange = { jsonText = it },
                                placeholder = { Text("{\n  \"baseUri\": \"https://...\",\n  \"password\": \"...\"\n}") },
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Button(
                                onClick = {
                                    val parsed = onParseJson(jsonText)
                                    if (parsed != null) {
                                        url = parsed.baseUri
                                        password = parsed.password
                                        showJsonInput = false
                                        jsonText = ""
                                        errorMessage = null
                                    } else {
                                        errorMessage = "Geçersiz JSON formatı! 'baseUri' veya 'password' bulunamadı."
                                    }
                                },
                                modifier = Modifier.align(Alignment.End).height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Text("Ayrıştır ve Doldur", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Base URL
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; errorMessage = null },
                    label = { Text("Mirror URL (Base URI)") },
                    placeholder = { Text("https://go.srcdl1.xyz/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Base64 Password
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("Arşiv Parolası (Base64)") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Gizle" else "Göster", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Embedded API Key Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔑", style = MaterialTheme.typography.titleSmall)
                        Column {
                            Text("API Anahtarı: Entegre / Aktif", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                            Text("Yetkilendirme anahtarı uygulamaya güvenli şekilde gömülüdür; manuel giriş gerekmez.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (url.isBlank()) {
                        errorMessage = "Lütfen geçerli bir sunucu URL'si girin."
                        return@Button
                    }
                    onSaveAndFetch(url, password)
                    onDismiss()
                }
            ) {
                Text("Kaydet ve Kataloğu Güncelle")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

@Composable
fun LocalSideloadTab(
    installedPackages: List<String>,
    isWorking: Boolean,
    hasDevice: Boolean,
    onSelectTarget: (File) -> Unit,
    onBackupPackage: (String) -> Unit,
    onUninstallPackage: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(modifier = Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Otomatik Kurulum (APK + OBB)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "İçinde APK ve OBB dosyaları bulunan bir oyun klasörünü veya doğrudan tek bir APK dosyasını seçin. Paket adı ve OBB yolu otomatik algılanır ve kurulur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = hasDevice && !isWorking,
                        onClick = {
                            val chooser = JFileChooser().apply {
                                fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                                dialogTitle = "Oyun Klasörü veya APK Seçin"
                            }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                onSelectTarget(chooser.selectedFile)
                            }
                        }
                    ) {
                        Text("📂 Klasör veya APK Seç & Kur")
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
            Text("Cihazda Yüklü Paketler (${installedPackages.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (installedPackages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Bağlı cihazda yüklü 3. parti paket bulunamadı veya cihaz bağlı değil.", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(installedPackages) { pkg ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(pkg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { onBackupPackage(pkg) }, enabled = !isWorking) {
                                        Text("Save Yedekle")
                                    }
                                    TextButton(onClick = { onUninstallPackage(pkg) }, enabled = !isWorking) {
                                        Text("Kaldır", color = Color(0xFFEF5350))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GameGridCard(
    item: RemotePackage,
    thumbnailFile: File?,
    isWorking: Boolean,
    hasDevice: Boolean,
    onDownloadOnly: (RemotePackage) -> Unit,
    onDownloadAndInstall: (RemotePackage) -> Unit
) {
    val bitmap = remember(thumbnailFile?.absolutePath) {
        ThumbnailCache.get(thumbnailFile)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color(0xFF1A1A24)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🎮", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.packageName.takeLast(24),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            maxLines = 1
                        )
                    }
                }

                // Popularity Badge
                if (item.downloadCount > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFE11D48).copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "🔥 ${item.downloads}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Size Badge
                if (item.sizeInfo.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.8f)
                    ) {
                        Text(
                            text = formatSize(item.sizeInfo),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Info & Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val details = buildList {
                    if (item.versionCode.isNotBlank()) add("v${item.versionCode}")
                    if (item.lastUpdated.isNotBlank()) add(item.lastUpdated.substringBefore(" "))
                }.joinToString(" • ")

                Text(
                    text = if (details.isNotBlank()) "$details • ${item.packageName}" else item.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onDownloadOnly(item) },
                        enabled = !isWorking,
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("İndir", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = { onDownloadAndInstall(item) },
                        enabled = hasDevice && !isWorking,
                        modifier = Modifier.weight(1.3f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("İndir & Kur", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun GameListRow(
    item: RemotePackage,
    thumbnailFile: File?,
    isWorking: Boolean,
    hasDevice: Boolean,
    onDownloadOnly: (RemotePackage) -> Unit,
    onDownloadAndInstall: (RemotePackage) -> Unit
) {
    val bitmap = remember(thumbnailFile?.absolutePath) {
        ThumbnailCache.get(thumbnailFile)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF202026)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("🎮", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val subtitle = buildList {
                    if (item.folderName.isNotBlank()) add(item.folderName)
                    if (item.lastUpdated.isNotBlank()) add(item.lastUpdated)
                }.joinToString(" • ")
                Text(
                    text = subtitle.ifBlank { item.packageName },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.sizeInfo.isNotBlank()) {
                        Text(formatSize(item.sizeInfo), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (item.downloadCount > 0) {
                        Text("🔥 Popülerlik: ${item.downloads}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFA726))
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            OutlinedButton(onClick = { onDownloadOnly(item) }, enabled = !isWorking, modifier = Modifier.height(36.dp)) {
                Text("İndir")
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { onDownloadAndInstall(item) },
                enabled = hasDevice && !isWorking,
                modifier = Modifier.height(36.dp)
            ) {
                Text("İndir ve Kur")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLibraryTab(
    remotePackages: List<RemotePackage>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    deleteAfterInstall: Boolean,
    onToggleDelete: (Boolean) -> Unit,
    isWorking: Boolean,
    hasDevice: Boolean,
    onRefreshCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadOnly: (RemotePackage) -> Unit,
    onDownloadAndInstall: (RemotePackage) -> Unit,
    getThumbnailFile: (String) -> File?
) {
    var isGridView by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf("Tümü") }
    var selectedSort by remember { mutableStateOf(SortOption.POPULARITY_DESC) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val categories = listOf(
        "Tümü",
        "🎮 VR Oyunları",
        "👓 Karma Gerçeklik (MR)",
        "🛠️ Araçlar",
        "🚀 Başlatıcılar",
        "📦 Büyük (+2GB)"
    )

    val filteredList = remember(remotePackages, searchQuery, selectedCategory, selectedSort) {
        val filtered = remotePackages.filter { item ->
            val matchesCategory = when (selectedCategory) {
                "🎮 VR Oyunları" -> !item.displayName.startsWith("_")
                "👓 Karma Gerçeklik (MR)" -> item.displayName.contains("MR", ignoreCase = true) || item.displayName.contains("Mixed Reality", ignoreCase = true)
                "🛠️ Araçlar" -> item.displayName.startsWith("_Utility", ignoreCase = true)
                "🚀 Başlatıcılar" -> item.displayName.startsWith("_AppLauncher", ignoreCase = true)
                "📦 Büyük (+2GB)" -> item.sizeMb >= 2048.0
                else -> true
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                "${item.displayName} ${item.folderName} ${item.packageName}".contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }

        when (selectedSort) {
            SortOption.POPULARITY_DESC -> filtered.sortedByDescending { it.downloadCount }
            SortOption.NAME_ASC -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            SortOption.NAME_DESC -> filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            SortOption.SIZE_DESC -> filtered.sortedByDescending { it.sizeMb }
            SortOption.SIZE_ASC -> filtered.sortedBy { it.sizeMb }
            SortOption.RECENT_DESC -> filtered.sortedByDescending { it.lastUpdated }
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Search & Action Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Oyun, paket veya anahtar kelime ara...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Text("✕", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            )

            // Sort Dropdown Button
            Box {
                OutlinedButton(
                    onClick = { sortMenuExpanded = true },
                    modifier = Modifier.height(54.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(selectedSort.title, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    Text("▾")
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    option.title,
                                    fontWeight = if (option == selectedSort) FontWeight.Bold else FontWeight.Normal,
                                    color = if (option == selectedSort) MaterialTheme.colorScheme.primary else Color.Unspecified
                                )
                            },
                            onClick = {
                                selectedSort = option
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }

            // Refresh Catalog Button
            Button(
                onClick = onRefreshCatalog,
                enabled = !isWorking,
                modifier = Modifier.height(54.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text("🔄 Kataloğu Yenile")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteAfterInstall, onCheckedChange = onToggleDelete)
                Text("Kurulum sonrası temizle", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Categories & View Toggle Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Grid / List Toggle & Count Badge
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "${filteredList.size} paket",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                OutlinedButton(
                    onClick = { isGridView = true },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = if (isGridView) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text("🎴 Kart", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = { isGridView = false },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = if (!isGridView) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text("📋 Liste", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Content Area: Grid or List
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (remotePackages.isEmpty() && !isWorking) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📚", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Katalogda henüz oyun bulunamadı.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Bulut sunucu ayarlarını yapılandırıp kataloğu güncelleyin.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRefreshCatalog) { Text("🔄 Kataloğu Çek") }
                        OutlinedButton(onClick = onOpenSettings) { Text("⚙️ Sunucu Ayarları") }
                    }
                }
            } else if (filteredList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Arama kriterinize uygun paket bulunamadı.", color = Color.Gray)
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 210.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredList) { item ->
                        val thumbFile = remember(item.packageName) {
                            getThumbnailFile(item.packageName)
                        }
                        GameGridCard(
                            item = item,
                            thumbnailFile = thumbFile,
                            isWorking = isWorking,
                            hasDevice = hasDevice,
                            onDownloadOnly = onDownloadOnly,
                            onDownloadAndInstall = onDownloadAndInstall
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filteredList) { item ->
                        val thumbFile = remember(item.packageName) {
                            getThumbnailFile(item.packageName)
                        }
                        GameListRow(
                            item = item,
                            thumbnailFile = thumbFile,
                            isWorking = isWorking,
                            hasDevice = hasDevice,
                            onDownloadOnly = onDownloadOnly,
                            onDownloadAndInstall = onDownloadAndInstall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuestTweaksTab(
    isWorking: Boolean,
    hasDevice: Boolean,
    onSetHz: (Int) -> Unit,
    onSetResolution: (Int, Int) -> Unit,
    onSetProximity: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ekran Yenileme Hızı (Refresh Rate)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Quest ekranınızın saniyedeki kare yenileme hızını ayarlayın.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(72, 80, 90, 120).forEach { hz ->
                        Button(onClick = { onSetHz(hz) }, enabled = hasDevice && !isWorking) {
                            Text("$hz Hz")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Göz İçi Doku Çözünürlüğü (Texture Resolution)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Daha keskin grafikler için göz içi render çözünürlüğünü artırın.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onSetResolution(1536, 1536) }, enabled = hasDevice && !isWorking) { Text("Standart (1536p)") }
                    Button(onClick = { onSetResolution(2048, 2048) }, enabled = hasDevice && !isWorking) { Text("Yüksek (2048p)") }
                    Button(onClick = { onSetResolution(2560, 2560) }, enabled = hasDevice && !isWorking) { Text("Ultra - Quest 3 (2560p)") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yakınlık Sensörü (Proximity Sensor)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Kapatıldığında, gözlüğü kafanızdan çıkarsanız dahi ekran kapanmaz ve arka plandaki indirmeler/kurulumlar kesintiye uğramaz.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onSetProximity(false) }, enabled = hasDevice && !isWorking) { Text("Sensörü Devre Dışı Bırak") }
                    Button(onClick = { onSetProximity(true) }, enabled = hasDevice && !isWorking) { Text("Sensörü Normal Haline Getir") }
                }
            }
        }
    }
}

fun main() = application {
    val adb = AdbController("adb")
    val rclone = RcloneController()
    val installer = ApkInstallerService(adb)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Quest Sideloader Studio",
        state = rememberWindowState(width = 1140.dp, height = 820.dp)
    ) {
        SideloaderApp(adb, rclone, installer)
    }
}

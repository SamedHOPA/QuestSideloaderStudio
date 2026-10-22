import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

@Composable
fun SideloaderApp(
    adbController: AdbController,
    rcloneController: RcloneController,
    installerService: ApkInstallerService
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var devices by remember { mutableStateOf<List<ConnectedDevice>>(emptyList()) }
    var installedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var remotePackages by remember { mutableStateOf(rcloneController.loadCachedPackages()) }
    var searchQuery by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf("Sistem hazır. Cihaz bekleniyor...")) }
    var isWorking by remember { mutableStateOf(false) }
    var deleteAfterInstall by remember { mutableStateOf(true) }

    // Public Config State (URL & Password)
    val initialConfig = remember { rcloneController.loadSavedConfig() }
    var mirrorUrl by remember { mutableStateOf(initialConfig.baseUri) }
    var mirrorPassword by remember { mutableStateOf(initialConfig.password) }
    var mirrorApiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var pausedPackage by remember { mutableStateOf<RemotePackage?>(null) }
    var installAfterResume by remember { mutableStateOf(false) }

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
                appendLog("⚠️ Bağlı cihaz bulunamadı. USB Hata Ayıklama iznini kontrol edin.")
                installedPackages = emptyList()
            }
            isWorking = false
        }
    }

    fun download(pkg: RemotePackage, install: Boolean) {
        downloadJob = scope.launch {
            isWorking = true
            downloading = true
            pausedPackage = pkg
            installAfterResume = install
            try {
                appendLog("⬇️ '${pkg.displayName}' indiriliyor; varsa kalan dosyalardan devam edilecek.")
                val downloaded = rcloneController.downloadPackage(
                    packageFolder = pkg.folderName, localDestDir = tempDownloadDir,
                    onProgressLine = { appendLog(it) }
                ).getOrThrow()
                downloading = false
                pausedPackage = null
                if (install) {
                    appendLog("📦 İndirme tamamlandı; cihaza kuruluyor…")
                    installerService.sideloadDirectoryOrApk(downloaded, rcloneController.archivePassword()) { appendLog(it) }
                        .getOrThrow()
                    appendLog("🎉 '${pkg.displayName}' başarıyla kuruldu!")
                    if (deleteAfterInstall) {
                        downloaded.parentFile.deleteRecursively()
                        appendLog("🧹 İndirme dosyaları temizlendi.")
                    }
                    refreshDeviceData()
                } else {
                    appendLog("✔️ İndirme tamamlandı: ${downloaded.absolutePath}")
                }
            } catch (cancel: CancellationException) {
                appendLog("⏸️ '${pkg.displayName}' indirmesi duraklatıldı. Devam ettirebilirsiniz.")
            } catch (e: Exception) {
                appendLog("❌ İndirme hatası: ${e.message}")
            } finally {
                downloading = false
                isWorking = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDeviceData()
        if (remotePackages.isNotEmpty()) {
            appendLog("📚 Önbellekten ${remotePackages.size} adet oyun kütüphaneye yüklendi.")
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activeDevice = devices.firstOrNull()
                        Column {
                            Text("Quest & Android Sideloader Studio", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (activeDevice != null) "Bağlı Cihaz: ${activeDevice.model} (${activeDevice.serial})" else "Cihaz Bağlı Değil",
                                color = if (activeDevice != null) Color(0xFF4CAF50) else Color(0xFFF44336),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { refreshDeviceData() }, enabled = !isWorking) {
                                Text("Cihazı Yenile")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isWorking = true
                                        appendLog("📡 Kablosuz ADB (Wi-Fi) başlatılıyor...")
                                        val res = adbController.enableWirelessAdb()
                                        appendLog(res.getOrElse { "Hata: ${it.message}" })
                                        refreshDeviceData()
                                        isWorking = false
                                    }
                                },
                                enabled = devices.isNotEmpty() && !isWorking
                            ) {
                                Text("Wi-Fi ADB Aktifleştir")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("📦 Yerel Kurulum & Yüklü Oyunlar") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("☁️ Bulut Kütüphanesi (${remotePackages.size})") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("⚙️ Quest Donanım & İnce Ayarlar") })
                }

                Spacer(Modifier.height(12.dp))

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
                            mirrorUrl = mirrorUrl,
                            onMirrorUrlChange = { mirrorUrl = it },
                            mirrorPassword = mirrorPassword,
                            onMirrorPasswordChange = { mirrorPassword = it },
                            mirrorApiKey = mirrorApiKey,
                            onMirrorApiKeyChange = { mirrorApiKey = it },
                            downloading = downloading,
                            onPause = { downloadJob?.cancel() },
                            onResume = if (pausedPackage != null && !isWorking) ({ download(pausedPackage!!, installAfterResume) }) else null,
                            onDownloadOnly = { download(it, false) },
                            onParseJson = { rawJson ->
                                val parsed = rcloneController.parsePublicConfig(rawJson)
                                if (parsed != null) {
                                    mirrorUrl = parsed.baseUri
                                    mirrorPassword = parsed.password
                                    if (parsed.apiKey.isNotBlank()) mirrorApiKey = parsed.apiKey
                                    appendLog("📋 Ayarlar içe aktarıldı.")
                                    true
                                } else {
                                    appendLog("❌ Geçersiz JSON formatı! 'baseUri' bulunamadı.")
                                    false
                                }
                            },
                            deleteAfterInstall = deleteAfterInstall,
                            onToggleDelete = { deleteAfterInstall = it },
                            isWorking = isWorking,
                            hasDevice = devices.isNotEmpty(),
                            onSaveAndFetchRemoteList = { url, pass ->
                                scope.launch {
                                    isWorking = true
                                    try {
                                        withContext(Dispatchers.IO) { rcloneController.saveConfig(PublicConfig(url, pass, mirrorApiKey)) }
                                        pausedPackage = null
                                        remotePackages = withContext(Dispatchers.IO) { rcloneController.loadCachedPackages() }
                                        appendLog("☁️ Katalog güncelleniyor...")
                                        remotePackages = rcloneController.listRemotePackages().getOrThrow()
                                        appendLog("✅ ${remotePackages.size} paket listelendi.")
                                    } catch (e: Exception) { appendLog("❌ ${e.message}") }
                                    finally { isWorking = false }
                                }
                            },
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

                Spacer(Modifier.height(12.dp))

                if (isWorking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                }
                Text("İşlem Kayıtları (Canlı Log)", style = MaterialTheme.typography.labelMedium)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(140.dp).background(Color(0xFF101010)).padding(8.dp)
                ) {
                    items(logs) { log ->
                        Text(log, fontFamily = FontFamily.Monospace, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
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
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Otomatik Kurulum (APK + OBB)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "İçinde APK ve OBB dosyaları bulunan bir oyun klasörünü veya doğrudan tek bir APK dosyasını seçin. Paket adı otomatik algılanır.",
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
            Text("Cihazda Yüklü 3. Parti Paketler (${installedPackages.size})", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(installedPackages) { pkg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pkg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Thumbnail Image or Fallback Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color(0xFF1E1E24)),
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
                        color = Color(0xFFD32F2F).copy(alpha = 0.85f)
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
                        color = Color.Black.copy(alpha = 0.75f)
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
            // Thumbnail
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

enum class SortOption(val title: String) {
    POPULARITY_DESC("🔥 En Popüler"),
    NAME_ASC("🔤 İsim (A-Z)"),
    NAME_DESC("🔤 İsim (Z-A)"),
    SIZE_DESC("📦 Boyut (En Büyük)"),
    SIZE_ASC("📦 Boyut (En Küçük)"),
    RECENT_DESC("🕒 Son Güncellenen")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLibraryTab(
    remotePackages: List<RemotePackage>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    mirrorUrl: String,
    onMirrorUrlChange: (String) -> Unit,
    mirrorPassword: String,
    onMirrorPasswordChange: (String) -> Unit,
    mirrorApiKey: String,
    onMirrorApiKeyChange: (String) -> Unit,
    downloading: Boolean,
    onPause: () -> Unit,
    onResume: (() -> Unit)?,
    onDownloadOnly: (RemotePackage) -> Unit,
    onParseJson: (String) -> Boolean,
    deleteAfterInstall: Boolean,
    onToggleDelete: (Boolean) -> Unit,
    isWorking: Boolean,
    hasDevice: Boolean,
    onSaveAndFetchRemoteList: (String, String) -> Unit,
    onDownloadAndInstall: (RemotePackage) -> Unit,
    getThumbnailFile: (String) -> File?
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var pastedJsonText by remember { mutableStateOf("") }
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

    if (showJsonDialog) {
        AlertDialog(
            onDismissRequest = { showJsonDialog = false },
            title = { Text("Public Config (JSON) Yapıştır") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Telegram, Discord veya topluluktan aldığınız JSON bloğunu buraya yapıştırın:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = pastedJsonText,
                        onValueChange = { pastedJsonText = it },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        placeholder = { Text("{\n  \"baseUri\": \"https://...\",\n  \"password\": \"...\"\n}") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (onParseJson(pastedJsonText)) {
                        showJsonDialog = false
                        pastedJsonText = ""
                    }
                }) {
                    Text("Ayarları İçe Aktar")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showJsonDialog = false }) {
                    Text("İptal")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Public Mirror Configuration Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Bulut Sunucu & Şifre Ayarları (Public Mirror)", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(
                        onClick = { showJsonDialog = true },
                        enabled = !isWorking,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("📋 Hızlı JSON Yapıştır", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = mirrorUrl,
                        onValueChange = onMirrorUrlChange,
                        label = { Text("Mirror URL (Base URI)") },
                        modifier = Modifier.weight(0.55f),
                        singleLine = true,
                        enabled = !isWorking
                    )

                    OutlinedTextField(
                        value = mirrorPassword,
                        onValueChange = onMirrorPasswordChange,
                        label = { Text("JSON Arşiv Parolası (Base64)") },
                        modifier = Modifier.weight(0.45f),
                        singleLine = true,
                        enabled = !isWorking,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "Gizle" else "Göster", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )

                    Button(
                        onClick = { onSaveAndFetchRemoteList(mirrorUrl, mirrorPassword) },
                        enabled = !isWorking,
                        modifier = Modifier.height(54.dp)
                    ) {
                        Text("Listeyi Güncelle")
                    }
                }

                OutlinedTextField(
                    value = mirrorApiKey, onValueChange = onMirrorApiKeyChange,
                    label = { Text("Sunucu API Anahtarı") },
                    supportingText = { Text("Windows hesabınıza bağlı şifreli olarak saklanır. Arşiv parolasından farklıdır.") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !isWorking, modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (downloading) OutlinedButton(onClick = onPause) { Text("İndirmeyi Duraklat") }
                    if (onResume != null) OutlinedButton(onClick = onResume) { Text("İndirmeye Devam Et") }
                    if (isWorking) Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Search, Sort & Options Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Oyun / Paket Ara...") },
                modifier = Modifier.weight(1f),
                singleLine = true
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filteredList.size} paket",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(end = 4.dp)
                )

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
            if (filteredList.isEmpty()) {
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
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ekran Yenileme Hızı (Refresh Rate)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Text("Göz İçi Doku Çözünürlüğü (Texture Resolution)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onSetResolution(1536, 1536) }, enabled = hasDevice && !isWorking) { Text("Standart (1536p)") }
                    Button(onClick = { onSetResolution(2048, 2048) }, enabled = hasDevice && !isWorking) { Text("Yüksek (2048p)") }
                    Button(onClick = { onSetResolution(2560, 2560) }, enabled = hasDevice && !isWorking) { Text("Ultra - Quest 3 (2560p)") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yakınlık Sensörü (Proximity Sensor)", style = MaterialTheme.typography.titleMedium)
                Text("Kapatıldığında, gözlüğü kafanızdan çıkarsanız bile ekran kapanmaz ve indirmeler kesilmez.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        title = "Quest & Android Sideloader Studio",
        state = rememberWindowState(width = 1100.dp, height = 800.dp)
    ) {
        SideloaderApp(adb, rclone, installer)
    }
}

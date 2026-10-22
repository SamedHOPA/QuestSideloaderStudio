import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import javax.swing.JFileChooser

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
    var remotePackages by remember { mutableStateOf<List<RemotePackage>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf("Sistem hazır. Cihaz bekleniyor...")) }
    var isWorking by remember { mutableStateOf(false) }
    var deleteAfterInstall by remember { mutableStateOf(true) }

    val tempDownloadDir = remember { File(System.getProperty("user.dir"), "downloads").apply { mkdirs() } }
    val backupBaseDir = remember { File(System.getProperty("user.dir"), "backups").apply { mkdirs() } }

    fun appendLog(msg: String) {
        val time = LocalTime.now().withNano(0)
        logs = (logs + "[$time] $msg").takeLast(200)
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

    LaunchedEffect(Unit) { refreshDeviceData() }

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
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("☁️ Bulut Kütüphanesi (Rclone)") })
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
                                    val res = installerService.sideloadDirectoryOrApk(targetFile) { appendLog(it) }
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
                            onFetchRemoteList = { remoteName ->
                                scope.launch {
                                    isWorking = true
                                    appendLog("☁️ '$remoteName' sunucusundan oyun listesi çekiliyor...")
                                    val res = rcloneController.listRemotePackages(remoteName)
                                    res.onSuccess { list ->
                                        remotePackages = list
                                        appendLog("✅ ${list.size} adet paket listelendi.")
                                    }.onFailure { err ->
                                        appendLog("❌ Liste çekilemedi: ${err.message}")
                                    }
                                    isWorking = false
                                }
                            },
                            onDownloadAndInstall = { remoteName, pkg ->
                                scope.launch {
                                    isWorking = true
                                    appendLog("⬇️ '${pkg.folderName}' indiriliyor...")
                                    val dlRes = rcloneController.downloadPackage(
                                        remoteName = remoteName,
                                        packageFolder = pkg.folderName,
                                        localDestDir = tempDownloadDir,
                                        onProgressLine = { appendLog("   $it") }
                                    )
                                    dlRes.onSuccess { downloadedDir ->
                                        appendLog("✔️ İndirme tamamlandı, kurulum başlıyor...")
                                        val instRes = installerService.sideloadDirectoryOrApk(downloadedDir) { appendLog(it) }
                                        if (instRes.isSuccess) {
                                            appendLog("🎉 '${pkg.folderName}' başarıyla kuruldu!")
                                            if (deleteAfterInstall) {
                                                downloadedDir.deleteRecursively()
                                                appendLog("🧹 Geçici indirme dosyaları temizlendi.")
                                            }
                                            refreshDeviceData()
                                        } else {
                                            appendLog("❌ Kurulum hatası: ${instRes.exceptionOrNull()?.message}")
                                        }
                                    }.onFailure { err ->
                                        appendLog("❌ İndirme hatası: ${err.message}")
                                    }
                                    isWorking = false
                                }
                            }
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
fun CloudLibraryTab(
    remotePackages: List<RemotePackage>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    deleteAfterInstall: Boolean,
    onToggleDelete: (Boolean) -> Unit,
    isWorking: Boolean,
    hasDevice: Boolean,
    onFetchRemoteList: (String) -> Unit,
    onDownloadAndInstall: (String, RemotePackage) -> Unit
) {
    var remoteName by remember { mutableStateOf("quest-mirror") }
    val filteredList = remember(remotePackages, searchQuery) {
        if (searchQuery.isBlank()) remotePackages
        else remotePackages.filter { it.folderName.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Oyun / Paket Ara...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = remoteName,
                onValueChange = { remoteName = it },
                label = { Text("Rclone Sunucu Adı") },
                modifier = Modifier.width(180.dp),
                singleLine = true
            )
            Button(onClick = { onFetchRemoteList(remoteName) }, enabled = !isWorking, modifier = Modifier.height(54.dp)) {
                Text("Listeyi Güncelle")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = deleteAfterInstall, onCheckedChange = onToggleDelete)
            Text("Kurulum tamamlandıktan sonra inen dosyaları bilgisayardan otomatik sil", style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filteredList) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.folderName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { onDownloadAndInstall(remoteName, item) },
                            enabled = hasDevice && !isWorking
                        ) {
                            Text("İndir ve Kur")
                        }
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
    val rclone = RcloneController("rclone", "rclone.conf")
    val installer = ApkInstallerService(adb)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Quest & Android Sideloader Studio",
        state = rememberWindowState(width = 1050.dp, height = 750.dp)
    ) {
        SideloaderApp(adb, rclone, installer)
    }
}

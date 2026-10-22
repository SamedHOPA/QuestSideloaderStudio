import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class MirrorIntegrationTest {
    private fun temp(): File = Files.createTempDirectory("quest-protocol-test-").toFile()

    @Test fun fullCatalogAndSplitPackagePipelinePreservesCacheOnFailure() = runBlocking {
        val dir = temp()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var failMetadata = false
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        try {
            val catalog = File(dir, "VRP-GameList.txt").apply {
                writeText("Game Name;Release Name;Package;Version;Date;Size\nTitle;Test Release;com.test;1;2026-01-01;1\n")
            }
            val metadata = File(dir, "meta.7z")
            MirrorProcess.run(listOf(AppFiles.binary("7z"), "a", "-t7z", "-mhe=on", "-ptestpw", metadata.absolutePath, catalog.absolutePath))
            val apk = File(dir, "demo.apk").apply { writeBytes(ByteArray(7000) { ((it * 43) % 251).toByte() }) }
            val hash = MirrorProtocol.hash("Test Release")
            val partDir = File(dir, "server-parts").apply { mkdirs() }
            MirrorProcess.run(listOf(AppFiles.binary("7z"), "a", "-t7z", "-v400b", "-mhe=on", "-ptestpw", File(partDir, "$hash.7z").absolutePath, apk.absolutePath))
            server.createContext("/mirror/") { exchange ->
                requests.add(exchange.requestURI.path to exchange.requestHeaders.getFirst("X-API-Key"))
                val path = exchange.requestURI.path
                val isMeta = path == "/mirror/meta.7z"
                val isDirectory = path.endsWith('/')
                val data = when {
                    isMeta && failMetadata -> "<html>blocked</html>".toByteArray()
                    isMeta -> metadata.readBytes()
                    isDirectory -> partDir.listFiles().orEmpty().joinToString("") { "<a href=\"${it.name}\">${it.name}</a>" }.toByteArray()
                    else -> File(partDir, path.substringAfterLast('/')).readBytes()
                }
                exchange.responseHeaders.add("Content-Type", if (isDirectory) "text/html" else "application/octet-stream")
                exchange.responseHeaders.add("Content-Length", data.size.toString())
                exchange.responseHeaders.add("Last-Modified", "Thu, 01 Jan 2026 00:00:00 GMT")
                if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1)
                else { exchange.sendResponseHeaders(200, data.size.toLong()); exchange.responseBody.write(data) }
                exchange.close()
            }
            server.start()
            val controller = RcloneController(configFile = File(dir, "config.json"))
            controller.saveConfig(PublicConfig("http://127.0.0.1:${server.address.port}/mirror/", "dGVzdHB3", "test-api-key"))
            val packages = controller.listRemotePackages().getOrThrow()
            assertEquals("Test Release", packages.single().folderName)
            val content = controller.downloadPackage(packageFolder = packages.single().folderName, localDestDir = File(dir, "downloads")) {}.getOrThrow()
            assertContentEquals(apk.readBytes(), File(content, "demo.apk").readBytes())
            assertTrue(requests.all { it.second == "test-api-key" }, requests.toString())
            failMetadata = true
            assertTrue(controller.listRemotePackages().isFailure)
            assertEquals(packages, controller.loadCachedPackages())
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test fun releaseHashIncludesLfAndPreservesName() {
        assertEquals("25c3772a6a589daf3232483d4191a967", MirrorProtocol.hash("Test Release"))
        assertNotEquals(MirrorProtocol.hash("Test Release"), MirrorProtocol.hash("Test Release "))
    }
    @Test fun jsonAndCatalogMatchReferenceFields() {
        val config = MirrorProtocol.parseConfig("""{"baseUri":"https://example.org/mirror","password":"c2VjcmV0","unknown":true}""")
        assertEquals("secret", config.archivePassword())
        assertEquals("https://example.org/mirror/meta.7z", config.base().resolve("meta.7z").toString())
        val list = MirrorProtocol.parseCatalog("\uFEFFGame Name;Release Name;Package;Version;Date;Size\r\nTitle;Test Release;com.test;12;2026-01-01;100\r\n")
        assertEquals("Title", list.single().displayName)
        assertEquals("Test Release", list.single().folderName)
        assertEquals("100", list.single().sizeInfo)
        assertEquals("25c3772a6a589daf3232483d4191a967", list.single().remoteHash)
    }
    @Test fun invalidConfigAndCatalogAreRejected() {
        assertFails { MirrorProtocol.parseConfig("""{"baseUri":"https://a.test","password":"not-base64!"}""") }
        assertFails { PublicConfig("https://user:pass@a.test", "").base() }
        assertFails { PublicConfig("https://a.test/?token=1", "").base() }
        assertFails { MirrorProtocol.parseCatalog("<html>blocked</html>") }
        assertFails { MirrorProtocol.parseCatalog("a;b;c\nonly one column") }
        assertFails { PublicConfig("https://a.test", "", "key\r\nX: y").key() }
    }
    @Test fun traversalAndExternalLinksAreRejected() {
        listOf("../x", "C:/x", "/x", "\\\\host\\share", "a/../../x").forEach { assertFalse(MirrorProtocol.safeArchiveEntry(it)) }
        assertTrue(MirrorProtocol.safeArchiveEntry("game/Android/data/file"))
        val links = ResumableDownload.listLinks("""<a href="part.7z.001">ok</a><a href="../bad">bad</a><a href="https://evil.test/a">bad</a><a href="/pkg/">dir</a>""", URI("http://localhost/pkg/"))
        assertEquals(listOf(URI("http://localhost/pkg/part.7z.001")), links)
    }
    @Test fun secretsAreRedacted() {
        val redacted = MirrorProcess.redact("secret123\nX-API-Key: hidden\nAuthorization: Bearer hidden", listOf("secret123"))
        assertFalse("secret123" in redacted)
        assertFalse("hidden" in redacted)
    }
    @Test fun windowsKeyStorageRoundTripAndConfigNoPlaintext() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val dir = temp()
        try {
            val file = File(dir, "config.json")
            val controller = RcloneController(configFile = file)
            controller.saveConfig(PublicConfig("https://example.org/", "cHc=", "test-secret-key"))
            assertFalse(file.readText().contains("test-secret-key"))
            assertEquals("test-secret-key", RcloneController(configFile = file).loadSavedConfig().apiKey)
        } finally { dir.deleteRecursively() }
    }

    private fun withFileServer(ignoreRange: Boolean = false, badRange: Boolean = false, test: suspend (URI, MutableList<String>, ByteArray) -> Unit) = runBlocking {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests.add("${exchange.requestMethod} ${range.orEmpty()}")
            exchange.responseHeaders.add("Last-Modified", "Thu, 01 Jan 2026 00:00:00 GMT")
            exchange.responseHeaders.add("Content-Length", data.size.toString())
            if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1)
            else {
                val start = if (!ignoreRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
                if (range != null && !ignoreRange) {
                    exchange.responseHeaders.set("Content-Range", "bytes ${if (badRange) start + 1 else start}-${data.size - 1}/${data.size}")
                    exchange.sendResponseHeaders(206, (data.size - start).toLong())
                } else exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.write(data.copyOfRange(start, data.size))
            }
            exchange.close()
        }
        server.start()
        try { test(URI("http://127.0.0.1:${server.address.port}/file"), requests, data) } finally { server.stop(0) }
    }
    private suspend fun seedPartial(uri: URI, file: File) {
        ResumableDownload.downloadFile(uri, file) {}
        java.io.RandomAccessFile(file, "rw").use { it.setLength(1000) }
    }
    @Test fun rangeResumeIsByteExact() = withFileServer { uri, requests, data ->
        val dir = temp()
        try {
            val file = File(dir, "file")
            seedPartial(uri, file)
            ResumableDownload.downloadFile(uri, file) {}
            assertTrue("GET bytes=1000-" in requests)
            assertContentEquals(data, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun ignoredRangeRestartsInsteadOfAppending() = withFileServer(ignoreRange = true) { uri, _, data ->
        val dir = temp()
        try {
            val file = File(dir, "file")
            seedPartial(uri, file)
            ResumableDownload.downloadFile(uri, file) {}
            assertContentEquals(data, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun wrongContentRangeDoesNotCorruptPartialFile() = withFileServer(badRange = true) { uri, _, data ->
        val dir = temp()
        try {
            val file = File(dir, "file")
            seedPartial(uri, file)
            assertFailsWith<IllegalStateException> { ResumableDownload.downloadFile(uri, file) {} }
            assertContentEquals(data.copyOfRange(0, 1000), file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun encryptedSplitArchiveExtractsAndWrongPasswordFails() = runBlocking {
        val dir = temp()
        try {
            val source = File(dir, "sample.apk").apply { writeBytes(ByteArray(5000) { (it % 127).toByte() }) }
            val archive = File(dir, "release.7z")
            MirrorProcess.run(listOf(AppFiles.binary("7z"), "a", "-t7z", "-v200b", "-mhe=on", "-ptestpw", archive.absolutePath, source.absolutePath))
            val first = File(dir, "release.7z.001")
            assertTrue(first.isFile)
            assertFails { ArchiveService.extract(first, File(dir, "bad"), "wrong") }
            val output = File(dir, "good")
            ArchiveService.extract(first, output, "testpw")
            assertContentEquals(source.readBytes(), File(output, "sample.apk").readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun actualRcloneProxySendsApiHeaderAndPreservesBasePath() = runBlocking {
        val received = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bytes = "part-content".toByteArray()
        server.createContext("/mirror/hash/") { exchange ->
            received.add(exchange.requestURI.path to exchange.requestHeaders.getFirst("X-API-Key"))
            val data = if (exchange.requestURI.path.endsWith('/')) "<a href=\"part.001\">part.001</a>".toByteArray() else bytes
            exchange.responseHeaders.add("Content-Type", if (exchange.requestURI.path.endsWith('/')) "text/html" else "application/octet-stream")
            exchange.responseHeaders.add("Content-Length", data.size.toString())
            exchange.responseHeaders.add("Last-Modified", "Thu, 01 Jan 2026 00:00:00 GMT")
            if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1)
            else { exchange.sendResponseHeaders(200, data.size.toLong()); exchange.responseBody.write(data) }
            exchange.close()
        }
        server.start()
        val dir = temp()
        try {
            MirrorProxy.start(AppFiles.binary("rclone"), "http://127.0.0.1:${server.address.port}/mirror/hash/",
                RcloneController.environment("test-api-key"), RcloneController.commonArgs(), "test-api-key").use { proxy ->
                try { ResumableDownload.download(proxy.url, dir) {} }
                catch (e: Exception) { throw proxy.failure(java.io.IOException("${e.message}; observed=$received; proxy=${proxy.url}", e)) }
            }
            assertContentEquals(bytes, File(dir, "part.001").readBytes())
            assertTrue(received.isNotEmpty())
            assertTrue(received.all { it.first.startsWith("/mirror/hash/") && it.second == "test-api-key" }, received.toString())
        } finally { server.stop(0); dir.deleteRecursively() }
    }
}

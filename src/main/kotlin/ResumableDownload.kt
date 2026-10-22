import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator
import kotlin.concurrent.thread

class MirrorProxy private constructor(private val process: Process, val url: URI, private val errors: StringBuilder) : Closeable {
    override fun close() { process.destroyForcibly(); process.inputStream.close() }
    fun failure(e: Exception): Exception = java.io.IOException("${e.message}\n${synchronized(errors) { errors.toString() }}", e)
    companion object {
        suspend fun start(binary: String, remoteUrl: String, environment: Map<String, String>, args: List<String>, key: String): MirrorProxy {
            // Point the HTTP remote at the package URL itself, retaining any base path.
            val command = listOf(binary, "serve", "http", ":http:", "--http-url", remoteUrl,
                "--addr", "127.0.0.1:0", "--read-only") + args
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment().putAll(environment)
            val process = builder.start()
            process.outputStream.close()
            val endpoint = CompletableFuture<URI>()
            val tail = StringBuilder()
            thread(isDaemon = true, name = "mirror-proxy") {
                try {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                        val clean = MirrorProcess.redact(line, listOf(key))
                        synchronized(tail) { tail.appendLine(clean); if (tail.length > 5000) tail.delete(0, tail.length - 5000) }
                        if (line.contains("Serving HTTP") || line.contains("HTTP Server started")) {
                            Regex("http://127\\.0\\.0\\.1:\\d+/?").findAll(line).lastOrNull()?.value?.let { endpoint.complete(URI(it.trimEnd('/') + "/")) }
                        }
                    } }
                } catch (_: Exception) { }
            }
            try {
                withTimeout(15000) {
                    while (!endpoint.isDone) {
                        if (!process.isAlive) error(MirrorProcess.explain(synchronized(tail) { tail.toString() }, process.exitValue()))
                        delay(100)
                    }
                }
                return MirrorProxy(process, endpoint.get(), tail)
            } catch (e: Exception) { process.destroyForcibly(); throw e }
        }
    }
}

private class HttpFailure(val status: Int, val waitMillis: Long, message: String) : java.io.IOException(message)

object ResumableDownload {
    fun listLinks(html: String, base: URI): List<URI> {
        val links = mutableListOf<URI>()
        ParserDelegator().parse(html.reader(), object : HTMLEditorKit.ParserCallback() {
            override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, pos: Int) {
                if (tag != HTML.Tag.A) return
                val href = attributes.getAttribute(HTML.Attribute.HREF)?.toString() ?: return
                if (href == ".." || href == "." || href.startsWith("#") || href.startsWith("?")) return
                val link = runCatching { base.resolve(href).normalize() }.getOrNull() ?: return
                if (link.scheme == base.scheme && link.authority == base.authority && link.query == null &&
                    link.fragment == null && link.path.startsWith(base.path) && link.path != base.path && !link.path.endsWith('/') &&
                    link.path.split('/').none { it == ".." || it == "." }) links.add(link)
            }
        }, true)
        return links.distinct()
    }
    private fun connection(uri: URI, method: String = "GET"): HttpURLConnection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 10000; readTimeout = 15000
        instanceFollowRedirects = false
        setRequestProperty("Accept-Encoding", "identity")
    }
    private fun checkResponse(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status in 200..299 || status == 416) return
        val retry = connection.getHeaderField("Retry-After")
        val seconds = retry?.toLongOrNull() ?: runCatching {
            java.time.Duration.between(java.time.Instant.now(), ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds
        }.getOrDefault(2)
        val body = connection.errorStream?.use { String(it.readNBytes(4096)) }.orEmpty()
        throw HttpFailure(status, seconds.coerceIn(1, 300) * 1000,
            "HTTP $status: ${body.replace(Regex("<[^>]*>"), " ").take(600)}")
    }
    suspend fun download(
        base: URI,
        destination: File,
        progress: (fraction: Float?, status: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        progress(null, "Bağlantı kuruluyor ve dosya listesi taranıyor...")
        val listing = retry {
            val conn = connection(base)
            try { checkResponse(conn); conn.inputStream.bufferedReader().use { it.readText() } }
            finally { conn.disconnect() }
        }
        val files = listLinks(listing, base)
        require(files.isNotEmpty()) { "Paket dizininde indirilebilir dosya yok." }
        for ((index, uri) in files.withIndex()) {
            ensureActive()
            val filename = URLDecoder.decode(uri.rawPath.substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            require(MirrorProtocol.safeArchiveEntry(filename) && '/' !in filename && '\\' !in filename && filename != ".") { "Geçersiz paket dosyası." }
            val partPrefix = if (files.size > 1) "[${index + 1}/${files.size}] " else ""
            progress(null, "${partPrefix}$filename hazırlanıyor...")
            retry { downloadFile(uri, File(destination, filename), partPrefix, progress) }
        }
    }
    private suspend fun <T> retry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try { return block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: java.io.IOException) {
                if (e is HttpFailure && e.status !in listOf(429, 500, 502, 503, 504)) throw e
                if (++attempt >= 3) throw e
                delay(if (e is HttpFailure) e.waitMillis else 1000L * attempt)
            }
        }
    }
    suspend fun downloadFile(
        uri: URI,
        file: File,
        partPrefix: String = "",
        progress: (fraction: Float?, status: String) -> Unit
    ) {
        val head = connection(uri, "HEAD")
        val size: Long
        val validator: String
        try {
            checkResponse(head)
            size = head.contentLengthLong
            validator = "${uri.rawPath}|$size|${head.getHeaderField("ETag")}|${head.getHeaderField("Last-Modified")}"
        } finally { head.disconnect() }
        val stamp = File(file.parentFile, file.name + ".resume")
        if (!stamp.isFile || stamp.readText() != validator || (size >= 0 && file.length() > size)) {
            if (file.exists()) check(file.delete()) { "Kısmi dosya sıfırlanamadı." }
            AppFiles.writeAtomic(stamp, validator)
        }
        var offset = if (file.isFile) file.length() else 0L
        if (size >= 0 && offset == size && file.isFile) return
        val conn = connection(uri)
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        try {
            checkResponse(conn)
            if (conn.responseCode == 416) {
                check(size >= 0 && file.length() == size) { "Sunucu aralığı reddetti; dosya boyutu doğrulanamadı (416)." }
                return
            }
            val partial = conn.responseCode == 206
            if (partial) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(conn.getHeaderField("Content-Range").orEmpty())
                check(range != null && range.groupValues[1].toLong() == offset) { "Sunucu yanlış Content-Range döndürdü." }
            } else {
                check(conn.responseCode == 200) { "Beklenmeyen HTTP yanıtı: ${conn.responseCode}" }
                offset = 0
            }
            var received = offset
            var lastUpdate = 0L
            val context = currentCoroutineContext()
            java.io.FileOutputStream(file, partial && offset > 0).use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(81920)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); received += count
                        if (System.currentTimeMillis() - lastUpdate >= 400) {
                            if (size > 0) {
                                val fraction = (received.toFloat() / size.toFloat()).coerceIn(0f, 1f)
                                val pct = (fraction * 100).toInt()
                                val curMb = received / 1048576
                                val totMb = size / 1048576
                                progress(fraction, "${partPrefix}${file.name}: %$pct ($curMb / $totMb MB)")
                            } else {
                                progress(null, "${partPrefix}${file.name}: ${received / 1048576} MB")
                            }
                            lastUpdate = System.currentTimeMillis()
                        }
                    }
                }
            }
            if (size >= 0 && received != size) throw java.io.IOException("Eksik indirme: $received / $size bayt. Tekrar deneniyor.")
            if (size >= 0) {
                val curMb = received / 1048576
                val totMb = size / 1048576
                progress(1f, "${partPrefix}${file.name}: %100 ($curMb / $totMb MB)")
            }
        } finally { conn.disconnect() }
    }
}

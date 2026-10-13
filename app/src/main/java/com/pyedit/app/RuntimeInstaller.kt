package com.pyedit.app

import android.content.Context
import android.os.StatFs
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads the official TypeScript compiler from the npm registry and
 * installs it under filesDir/runtimes/typescript. It lives in an object
 * with its own scope so leaving the screen doesn't cancel a download, and
 * so two installs can never run at once.
 */
object RuntimeInstaller {

    sealed class State {
        object NotInstalled : State()
        data class Downloading(val done: Long, val total: Long) : State() // total -1 = unknown
        object Installing : State()
        data class Installed(val version: String) : State()
        data class Failed(val message: String) : State()
        object Cancelled : State()
    }

    data class Sizes(val download: Long?, val installed: Long?)

    const val TS_VERSION = "5.4.5"
    private const val UA = "PyEdit-Android/1.0 (runtime installer)"

    private val _tsState = MutableStateFlow<State>(State.NotInstalled)
    val tsState: StateFlow<State> = _tsState

    private val _tsSizes = MutableStateFlow(Sizes(null, null))
    val tsSizes: StateFlow<Sizes> = _tsSizes

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun runtimesDir(context: Context) = File(context.filesDir, "runtimes")
    private fun tsDir(context: Context) = File(runtimesDir(context), "typescript")
    fun compilerFile(context: Context) = File(tsDir(context), "typescript.js")
    private fun metaFile(context: Context) = File(tsDir(context), "meta.json")

    /** Cheap synchronous check used by Run. */
    fun isTypeScriptUsable(context: Context): Boolean {
        return try {
            val f = compilerFile(context)
            val m = metaFile(context)
            f.exists() && m.exists() && f.length() == JSONObject(m.readText()).getLong("size")
        } catch (e: Exception) {
            false
        }
    }

    /** Reconciles saved metadata with the real files (never trusts a flag alone). */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val s = _tsState.value
            if (s is State.Downloading || s is State.Installing) return@launch
            File(app.cacheDir, "ts_download.tgz").delete()
            File(runtimesDir(app), "typescript.tmp").deleteRecursively()
            try {
                val meta = JSONObject(metaFile(app).readText())
                val f = compilerFile(app)
                if (f.exists() && f.length() == meta.getLong("size") &&
                    sha256Hex(f) == meta.getString("sha256")
                ) {
                    _tsState.value = State.Installed(meta.getString("version"))
                    return@launch
                }
            } catch (e: Exception) { /* fall through */ }
            if (tsDir(app).exists()) tsDir(app).deleteRecursively() // corrupt/partial -> not usable
            _tsState.value = State.NotInstalled
        }
    }

    fun prefetchSizes() {
        if (_tsSizes.value.download != null) return
        scope.launch {
            try {
                val dist = JSONObject(httpGetString("https://registry.npmjs.org/typescript/$TS_VERSION"))
                    .getJSONObject("dist")
                val unpacked = dist.optLong("unpackedSize", -1).takeIf { it > 0 }
                val head = URL(dist.getString("tarball")).openConnection() as HttpURLConnection
                head.requestMethod = "HEAD"
                head.setRequestProperty("Accept-Encoding", "identity")
                head.setRequestProperty("User-Agent", UA)
                val len = head.contentLengthLong.takeIf { it > 0 }
                head.disconnect()
                _tsSizes.value = Sizes(len, unpacked)
            } catch (e: Exception) { /* sizes stay Unknown */ }
        }
    }

    fun installTypeScript(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            val s = _tsState.value
            if (s is State.Downloading || s is State.Installing || s is State.Installed) return
            _tsState.value = State.Downloading(0, -1)
        }
        job = scope.launch {
            try {
                doInstall(app)
            } catch (e: CancellationException) {
                cleanupTemp(app)
                _tsState.value = State.Cancelled
                throw e
            } catch (e: Exception) {
                cleanupTemp(app)
                _tsState.value = State.Failed(e.message ?: "Installation failed")
            }
        }
    }

    /** Only the download stage is cancellable; extraction/verification always
     * finishes or rolls back, so a half-installed compiler can't exist. */
    fun cancelDownload() {
        if (_tsState.value is State.Downloading) job?.cancel()
    }

    private suspend fun doInstall(app: Context) {
        val meta = JSONObject(httpGetString("https://registry.npmjs.org/typescript/$TS_VERSION"))
        val dist = meta.getJSONObject("dist")
        val tarball = dist.getString("tarball")
        val integrity = dist.getString("integrity")
        require(integrity.startsWith("sha512-")) { "Registry gave no SHA-512 checksum" }
        val unpacked = dist.optLong("unpackedSize", 15L * 1024 * 1024)

        val conn = URL(tarball).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L

        val needed = (if (total > 0) total else 25L * 1024 * 1024) + unpacked + 20L * 1024 * 1024
        val free = StatFs(app.filesDir.path).availableBytes
        if (free < needed) {
            conn.disconnect()
            throw IOException("Not enough storage: about ${needed / (1024 * 1024)} MB free is needed")
        }

        val tgz = File(app.cacheDir, "ts_download.tgz")
        val sha512 = MessageDigest.getInstance("SHA-512")
        var done = 0L
        var lastPublish = 0L
        conn.inputStream.use { input ->
            FileOutputStream(tgz).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    sha512.update(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastPublish > 100) {
                        lastPublish = now
                        _tsState.value = State.Downloading(done, total)
                    }
                }
            }
        }
        conn.disconnect()
        if (total > 0 && done != total) throw IOException("Download was cut short")
        _tsState.value = State.Downloading(done, total)

        val actual = Base64.encodeToString(sha512.digest(), Base64.NO_WRAP)
        if (actual != integrity.removePrefix("sha512-")) {
            throw IOException("Checksum mismatch - the download is corrupt and was discarded")
        }

        // From here on the install can't be cancelled (see cancelDownload).
        _tsState.value = State.Installing
        val tmp = File(runtimesDir(app), "typescript.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val compiler = File(tmp, "typescript.js")
        extractFromTgz(tgz, mapOf("package/lib/typescript.js" to compiler))
        if (!compiler.exists() || compiler.length() == 0L) throw IOException("The archive did not contain the compiler")

        // Operational check: the compiler must actually transpile TypeScript.
        val check = TsTranspiler.transpile(compiler, "const x: number = 1;\nconsole.log(x);", "check.ts")
        val js = check.js
        if (js == null || !js.contains("x = 1") || js.contains(": number")) {
            throw IOException("The compiler failed its self-test and was not installed")
        }

        val m = JSONObject()
        m.put("version", TS_VERSION)
        m.put("size", compiler.length())
        m.put("sha256", sha256Hex(compiler))
        File(tmp, "meta.json").writeText(m.toString())

        // Swap in; keep the old copy until the new one is in place.
        val target = tsDir(app)
        val old = File(runtimesDir(app), "typescript.old")
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) throw IOException("Could not replace the existing install")
        if (!tmp.renameTo(target)) {
            if (old.exists()) old.renameTo(target)
            throw IOException("Could not finish the install")
        }
        old.deleteRecursively()
        tgz.delete()
        _tsState.value = State.Installed(TS_VERSION)
    }

    private fun cleanupTemp(app: Context) {
        File(app.cacheDir, "ts_download.tgz").delete()
        File(runtimesDir(app), "typescript.tmp").deleteRecursively()
    }

    private fun httpGetString(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw IOException("Registry returned HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256Hex(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    // --- minimal tar reader (npm tarballs are plain ustar + gzip) --------

    private fun extractFromTgz(tgz: File, wanted: Map<String, File>) {
        GZIPInputStream(BufferedInputStream(FileInputStream(tgz))).use { gz ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break
                val name = cstring(header, 0, 100)
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val target = wanted[name]
                if (target != null && (type == '0' || type == '\u0000')) {
                    FileOutputStream(target).use { out -> copyExactly(gz, out, size) }
                } else {
                    skipExactly(gz, size)
                }
                skipExactly(gz, (512 - size % 512) % 512)
            }
        }
    }

    private fun readFully(s: InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = s.read(b, off, b.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun cstring(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = cstring(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun copyExactly(i: InputStream, o: FileOutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Truncated archive")
            o.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipExactly(i: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Truncated archive")
            left -= n
        }
    }
}
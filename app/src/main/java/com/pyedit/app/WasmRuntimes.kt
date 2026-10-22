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
 * Downloads Ruby / PHP as WebAssembly builds from the npm registry (pinned
 * versions), verifies the SHA-512, extracts only the needed files and swaps
 * them in. They run in a hidden WebView (see WasmRunner).
 */
object WasmRuntimes {

    enum class Id(val dir: String, val label: String, val npmName: String, val version: String) {
        RUBY("ruby", "Ruby", "@ruby/3.3-wasm-wasi", "2.10.1"),
        PHP("php", "PHP", "php-wasm", "0.2.0")
    }

    private const val UA = "PyEdit-Android/1.0 (runtime installer)"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Id, Job>()
    private val states = Id.values().associateWith {
        MutableStateFlow<RuntimeInstaller.State>(RuntimeInstaller.State.NotInstalled)
    }
    // Ruby installed size = sum of the extracted files in the package file list; PHP's is unknown until installed.
    private val sizes = Id.values().associateWith {
        MutableStateFlow(RuntimeInstaller.Sizes(null, if (it == Id.RUBY) 36_171_524L else null))
    }

    fun state(id: Id): StateFlow<RuntimeInstaller.State> = states.getValue(id)
    fun sizes(id: Id): StateFlow<RuntimeInstaller.Sizes> = sizes.getValue(id)

    private fun root(ctx: Context) = File(ctx.filesDir, "runtimes")
    fun dir(ctx: Context, id: Id) = File(root(ctx), id.dir)

    /** Never trusts a saved flag: every recorded file must exist with its recorded size. */
    fun isUsable(ctx: Context, id: Id): Boolean = try {
        val d = dir(ctx, id)
        val files = JSONObject(File(d, "meta.json").readText()).getJSONObject("files")
        files.length() > 0 && files.keys().asSequence().all {
            val f = File(d, it)
            f.isFile && f.length() == files.getLong(it)
        }
    } catch (e: Exception) {
        false
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        for (id in Id.values()) scope.launch {
            val s = states.getValue(id).value
            if (s is RuntimeInstaller.State.Downloading || s is RuntimeInstaller.State.Installing) return@launch
            File(app.cacheDir, "wasm_${id.dir}.tgz").delete()
            File(root(app), id.dir + ".tmp").deleteRecursively()
            if (isUsable(app, id)) {
                val v = try { JSONObject(File(dir(app, id), "meta.json").readText()).getString("version") } catch (e: Exception) { id.version }
                states.getValue(id).value = RuntimeInstaller.State.Installed(v)
            } else {
                dir(app, id).deleteRecursively()
                states.getValue(id).value = RuntimeInstaller.State.NotInstalled
            }
        }
    }

    fun prefetchSizes() {
        for (id in Id.values()) {
            if (sizes.getValue(id).value.download != null) continue
            scope.launch {
                try {
                    val dist = JSONObject(httpString("https://registry.npmjs.org/${id.npmName}/${id.version}")).getJSONObject("dist")
                    val head = URL(dist.getString("tarball")).openConnection() as HttpURLConnection
                    head.requestMethod = "HEAD"
                    head.setRequestProperty("Accept-Encoding", "identity")
                    head.setRequestProperty("User-Agent", UA)
                    val len = head.contentLengthLong.takeIf { it > 0 }
                    head.disconnect()
                    val old = sizes.getValue(id).value
                    sizes.getValue(id).value = RuntimeInstaller.Sizes(len, old.installed)
                } catch (e: Exception) { /* stays Unknown */ }
            }
        }
    }

    fun install(ctx: Context, id: Id) {
        val app = ctx.applicationContext
        synchronized(this) {
            val s = states.getValue(id).value
            if (s is RuntimeInstaller.State.Downloading || s is RuntimeInstaller.State.Installing || s is RuntimeInstaller.State.Installed) return
            states.getValue(id).value = RuntimeInstaller.State.Downloading(0, -1)
        }
        jobs[id] = scope.launch {
            try {
                doInstall(app, id)
            } catch (e: CancellationException) {
                cleanup(app, id)
                states.getValue(id).value = RuntimeInstaller.State.Cancelled
                throw e
            } catch (e: Exception) {
                cleanup(app, id)
                states.getValue(id).value = RuntimeInstaller.State.Failed(e.message ?: "Installation failed")
            }
        }
    }

    /** Only the download is cancellable; extraction finishes or rolls back. */
    fun cancel(id: Id) {
        if (states.getValue(id).value is RuntimeInstaller.State.Downloading) jobs[id]?.cancel()
    }

    private fun cleanup(app: Context, id: Id) {
        File(app.cacheDir, "wasm_${id.dir}.tgz").delete()
        File(root(app), id.dir + ".tmp").deleteRecursively()
    }

    private suspend fun doInstall(app: Context, id: Id) {
        val dist = JSONObject(httpString("https://registry.npmjs.org/${id.npmName}/${id.version}")).getJSONObject("dist")
        val integrity = dist.getString("integrity")
        if (!integrity.startsWith("sha512-")) throw IOException("Registry gave no SHA-512 checksum")

        val conn = URL(dist.getString("tarball")).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L

        val needed = maxOf(total, 40L * 1024 * 1024) * 3 + 50L * 1024 * 1024
        if (StatFs(app.filesDir.path).availableBytes < needed) {
            conn.disconnect()
            throw IOException("Not enough storage: about ${needed / (1024 * 1024)} MB free is needed")
        }

        val tgz = File(app.cacheDir, "wasm_${id.dir}.tgz")
        val sha = MessageDigest.getInstance("SHA-512")
        var done = 0L
        var last = 0L
        conn.inputStream.use { ins ->
            FileOutputStream(tgz).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 100) { last = now; states.getValue(id).value = RuntimeInstaller.State.Downloading(done, total) }
                }
            }
        }
        conn.disconnect()
        if (total > 0 && done != total) throw IOException("Download was cut short")
        states.getValue(id).value = RuntimeInstaller.State.Downloading(done, total)
        if (Base64.encodeToString(sha.digest(), Base64.NO_WRAP) != integrity.removePrefix("sha512-")) {
            throw IOException("Checksum mismatch - the download is corrupt and was discarded")
        }

        states.getValue(id).value = RuntimeInstaller.State.Installing
        val stage = File(root(app), id.dir + ".tmp")
        stage.deleteRecursively()
        stage.mkdirs()

        if (id == Id.RUBY) {
            extract(tgz, stage) { full ->
                when (full) {
                    "package/dist/ruby+stdlib.wasm" -> "ruby+stdlib.wasm"
                    "package/dist/browser.umd.js" -> "browser.umd.js"
                    "package/dist/LICENSE" -> "LICENSE"
                    "package/dist/NOTICE" -> "NOTICE"
                    else -> null
                }
            }
        } else {
            extract(tgz, stage) { full ->
                val n = full.removePrefix("package/")
                when {
                    n.contains('/') -> null
                    n == "LICENSE" || n == "NOTICE" -> n
                    n.endsWith(".mjs") && (n == "php8.4-web.mjs" ||
                        (!n.startsWith("php8.") && !n.contains("node", ignoreCase = true))) -> n
                    else -> null
                }
            }
            val entry = File(stage, "php8.4-web.mjs")
            if (!entry.isFile) throw IOException("The PHP package has no php8.4-web.mjs")
            val hash = Regex("([0-9a-f]{40})\\.wasm").find(entry.readText())?.groupValues?.get(1)
                ?: throw IOException("Couldn't find the PHP WebAssembly file name")
            extract(tgz, stage) { full -> if (full == "package/$hash.wasm") "$hash.wasm" else null }
            if (!File(stage, "PhpWeb.mjs").isFile) throw IOException("The PHP package has no PhpWeb.mjs")
        }

        val files = JSONObject()
        val extracted = stage.listFiles()?.filter { it.isFile } ?: emptyList()
        if (extracted.isEmpty()) throw IOException("Nothing was extracted")
        for (f in extracted) {
            if (f.name.endsWith(".wasm")) {
                val magic = ByteArray(4)
                FileInputStream(f).use { it.read(magic) }
                if (!magic.contentEquals(byteArrayOf(0, 0x61, 0x73, 0x6D))) throw IOException("${f.name} is not a WebAssembly file")
            }
            files.put(f.name, f.length())
        }
        if (id == Id.RUBY && !(File(stage, "ruby+stdlib.wasm").isFile && File(stage, "browser.umd.js").isFile)) {
            throw IOException("The Ruby package is missing required files")
        }
        File(stage, "meta.json").writeText(JSONObject().put("version", id.version).put("files", files).toString())
        sizes.getValue(id).value = RuntimeInstaller.Sizes(sizes.getValue(id).value.download, extracted.sumOf { it.length() })

        val target = dir(app, id)
        val old = File(root(app), id.dir + ".old")
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) throw IOException("Couldn't replace the existing install")
        if (!stage.renameTo(target)) {
            if (old.exists()) old.renameTo(target)
            throw IOException("Couldn't finish the install")
        }
        old.deleteRecursively()
        tgz.delete()
        states.getValue(id).value = RuntimeInstaller.State.Installed(id.version)
    }

    private fun httpString(url: String): String {
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

    // --- tar.gz reader: pick(entryName) returns the output file name, or null to skip ---

    private fun extract(tgz: File, dest: File, pick: (String) -> String?) {
        val base = dest.canonicalFile
        GZIPInputStream(BufferedInputStream(FileInputStream(tgz))).use { gz ->
            val h = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (!readFully(gz, h)) break
                if (h.all { it == 0.toByte() }) break
                var name = cstr(h, 0, 100)
                if (cstr(h, 257, 6).startsWith("ustar")) {
                    val p = cstr(h, 345, 155)
                    if (p.isNotEmpty()) name = "$p/$name"
                }
                val size = octal(h, 124, 12)
                val pad = (512 - size % 512) % 512
                when (h[156].toInt().toChar()) {
                    'L' -> {
                        if (size > 100_000) throw IOException("Unexpected tar header")
                        val b = ByteArray(size.toInt())
                        if (!readFully(gz, b)) throw IOException("Truncated archive")
                        longName = String(b, Charsets.UTF_8).trimEnd('\u0000')
                        skip(gz, pad)
                    }
                    'x', 'g' -> skip(gz, size + pad)
                    '0', '\u0000' -> {
                        val full = longName ?: name
                        longName = null
                        val rel = pick(full)
                        if (rel == null) {
                            skip(gz, size + pad)
                        } else {
                            val out = File(base, rel).canonicalFile
                            if (!out.path.startsWith(base.path + File.separator)) throw IOException("Unsafe path in archive")
                            FileOutputStream(out).use { copyExactly(gz, it, size) }
                            skip(gz, pad)
                        }
                    }
                    else -> { longName = null; skip(gz, size + pad) }
                }
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

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = cstr(b, off, len).trim()
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

    private fun skip(i: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Truncated archive")
            left -= n
        }
    }
}
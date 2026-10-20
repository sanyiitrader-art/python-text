package com.pyedit.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Real npm install into filesDir/node_modules. Resolves dependencies against the
 * npm registry, downloads each tarball, verifies its checksum, extracts into a
 * staging folder and only then moves everything into place. Install scripts are
 * never run. One install at a time (mutex).
 */
object NpmInstaller {

    data class Result(val ok: Boolean, val message: String)

    private class NpmException(msg: String) : Exception(msg)

    private const val UA = "PyEdit-Android/1.0 (npm installer)"
    private const val MAX_PACKAGES = 200
    private val nameRe = Regex("^(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*$")
    private val mutex = Mutex()

    fun nodeModulesDir(ctx: Context) = File(ctx.filesDir, "node_modules")

    // --- what is installed ---------------------------------------------------

    private fun readVersion(dir: File): String? = try {
        JSONObject(File(dir, "package.json").readText()).optString("version").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** real top-level package name -> version */
    private fun scanInstalled(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        val top = nodeModulesDir(ctx).listFiles() ?: return out
        for (d in top) {
            if (!d.isDirectory || d.name.startsWith(".")) continue
            if (d.name.startsWith("@")) {
                d.listFiles()?.forEach { s ->
                    if (s.isDirectory) readVersion(s)?.let { out["${d.name}/${s.name}"] = it }
                }
            } else {
                readVersion(d)?.let { out[d.name] = it }
            }
        }
        return out
    }

    /** normalized name -> version (for the Download Library "Installed" state) */
    fun installedVersions(ctx: Context): Map<String, String> =
        scanInstalled(ctx).mapKeys { PackageCatalog.normalize(it.key) }

    // --- registry --------------------------------------------------------------

    private class Meta(
        val name: String,
        val version: String,
        val deps: Map<String, String>,
        val tarball: String,
        val integrity: String?,
        val shasum: String?
    )

    private class Packument(val tags: Map<String, String>, val versions: JSONObject)

    private class Task(val path: String, val meta: Meta)

    private class Plan(val placed: MutableMap<String, String>) {
        val cache = HashMap<String, Packument>()
        val tasks = ArrayList<Task>()
    }

    private fun open(url: String, accept: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        return c
    }

    private fun packument(name: String, plan: Plan): Packument {
        plan.cache[name]?.let { return it }
        val c = open("https://registry.npmjs.org/" + name.replace("/", "%2F"), "application/vnd.npm.install-v1+json")
        try {
            val code = c.responseCode
            if (code == 404) throw NpmException("'$name' was not found on npm")
            if (code != 200) throw NpmException("npm registry returned HTTP $code for '$name'")
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tags = HashMap<String, String>()
            root.optJSONObject("dist-tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.getString(k) } }
            val pk = Packument(tags, root.getJSONObject("versions"))
            plan.cache[name] = pk
            return pk
        } finally {
            c.disconnect()
        }
    }

    private fun pickVersion(pk: Packument, range: String, name: String): String {
        val r = range.trim()
        pk.tags[r]?.let { return it }
        if (r.isEmpty() || r == "*" || r == "latest") {
            pk.tags["latest"]?.let { return it }
        }
        val all = ArrayList<String>()
        pk.versions.keys().forEach { all.add(it) }
        return Semver.maxSatisfying(all, if (r.isEmpty()) "*" else r)
            ?: throw NpmException("No version of '$name' satisfies '$r'")
    }

    private fun metaFor(pk: Packument, version: String, name: String): Meta {
        val v = pk.versions.optJSONObject(version) ?: throw NpmException("'$name' has no version $version")
        val dist = v.optJSONObject("dist") ?: throw NpmException("'$name@$version' has no download information")
        val deps = HashMap<String, String>()
        v.optJSONObject("dependencies")?.let { d -> d.keys().forEach { k -> deps[k] = d.getString(k) } }
        return Meta(
            name, version, deps,
            dist.optString("tarball"),
            dist.optString("integrity").takeIf { it.isNotEmpty() },
            dist.optString("shasum").takeIf { it.isNotEmpty() }
        )
    }

    private fun parentOwner(o: String): String =
        if (o.contains("/node_modules/")) o.substringBeforeLast("/node_modules/") else ""

    /** Places [dep] the way Node would find it from [owner], reusing a satisfying copy if there is one. */
    private fun resolveDep(owner: String, dep: String, range: String, plan: Plan) {
        if (!nameRe.matches(dep)) throw NpmException("Invalid dependency name '$dep'")
        if (Semver.isUnsupportedSpec(range)) {
            throw NpmException("Dependency '$dep' uses an unsupported source ('$range'); only registry versions are supported")
        }
        val chain = ArrayList<String>()
        var o = owner
        while (true) {
            chain.add(if (o.isEmpty()) dep else "$o/node_modules/$dep")
            if (o.isEmpty()) break
            o = parentOwner(o)
        }
        val nearest = chain.firstOrNull { plan.placed.containsKey(it) }
        if (nearest != null) {
            val have = plan.placed.getValue(nearest)
            val isTagLike = range.isNotEmpty() && range[0].isLetter() && Semver.parse(range) == null && !range.equals("x", true)
            if (isTagLike || Semver.satisfies(have, range)) return
        }
        val target = if (nearest == null) dep else "$owner/node_modules/$dep"
        val pk = packument(dep, plan)
        val version = pickVersion(pk, range, dep)
        val meta = metaFor(pk, version, dep)
        plan.placed[target] = version
        plan.tasks.add(Task(target, meta))
        if (plan.tasks.size > MAX_PACKAGES) throw NpmException("Too many dependencies (limit $MAX_PACKAGES)")
        for ((d, r) in meta.deps) resolveDep(target, d, r, plan)
    }

    // --- install ---------------------------------------------------------------

    suspend fun install(ctx: Context, name: String): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = ctx.applicationContext
            val stage = File(app.filesDir, ".npm-stage")
            val tgz = File(app.cacheDir, "npm_download.tgz")
            stage.deleteRecursively()
            stage.mkdirs()
            try {
                doInstall(app, name.trim(), stage, tgz)
            } catch (e: NpmException) {
                Result(false, e.message ?: "Install failed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                Result(false, "Network or file error: ${e.message}")
            } catch (e: Exception) {
                Result(false, "Unexpected error: ${e.message}")
            } finally {
                stage.deleteRecursively()
                tgz.delete()
            }
        }
    }

    private suspend fun doInstall(app: Context, name: String, stage: File, tgz: File): Result {
        if (!nameRe.matches(name)) throw NpmException("'$name' isn't a valid npm package name")

        val plan = Plan(scanInstalled(app).toMutableMap())
        val rootPk = packument(name, plan)
        val rootVersion = pickVersion(rootPk, "latest", name)
        val rootMeta = metaFor(rootPk, rootVersion, name)
        plan.placed[name] = rootVersion // the requested package always replaces any older copy
        plan.tasks.add(Task(name, rootMeta))
        for ((d, r) in rootMeta.deps) resolveDep(name, d, r, plan)

        val warnings = ArrayList<String>()
        for (t in plan.tasks) {
            currentCoroutineContext().ensureActive()
            download(t.meta, tgz)
            extract(tgz, File(stage, t.path))
            verifyStaged(File(stage, t.path), t.meta, warnings)
        }

        // Commit: shallow first. A nested package moves with its parent when the
        // parent is itself staged, so skip it if it's already gone from staging.
        val nm = nodeModulesDir(app)
        nm.mkdirs()
        val moved = ArrayList<Pair<File, File?>>()
        try {
            for (t in plan.tasks.sortedBy { it.path.split("/node_modules/").size }) {
                val src = File(stage, t.path)
                if (!src.exists()) continue
                val dest = File(nm, t.path)
                dest.parentFile?.mkdirs()
                var backup: File? = null
                if (dest.exists()) {
                    backup = File(nm, ".old-${System.nanoTime()}")
                    if (!dest.renameTo(backup)) throw NpmException("Couldn't replace ${t.path}")
                }
                if (!src.renameTo(dest)) {
                    backup?.renameTo(dest)
                    throw NpmException("Couldn't move ${t.path} into place")
                }
                moved.add(Pair(dest, backup))
            }
        } catch (e: Exception) {
            for ((d, b) in moved.asReversed()) {
                d.deleteRecursively()
                b?.renameTo(d)
            }
            throw e
        }
        moved.forEach { it.second?.deleteRecursively() }

        val deps = plan.tasks.size - 1
        var msg = "Installed $name $rootVersion" + if (deps > 0) " (+$deps dependenc${if (deps == 1) "y" else "ies"})" else ""
        msg += ". Files and checksums verified. The package has not been run - whether it works here only shows when you " +
            "require() it (no Node.js built-ins, no install scripts, CommonJS only)."
        if (warnings.isNotEmpty()) msg += "\nNotes: " + warnings.joinToString("; ")
        return Result(true, msg)
    }

    private suspend fun download(meta: Meta, dest: File) {
        if (meta.tarball.isEmpty()) throw NpmException("${meta.name}@${meta.version} has no tarball URL")
        val sha512 = meta.integrity?.split(' ')?.firstOrNull { it.startsWith("sha512-") }
        if (sha512 == null && meta.shasum == null) throw NpmException("${meta.name}@${meta.version} has no checksum")
        val c = open(meta.tarball, null)
        try {
            if (c.responseCode != 200) throw NpmException("Download of ${meta.name} failed: HTTP ${c.responseCode}")
            val md = MessageDigest.getInstance(if (sha512 != null) "SHA-512" else "SHA-1")
            c.inputStream.use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
            }
            val ok = if (sha512 != null)
                Base64.encodeToString(md.digest(), Base64.NO_WRAP) == sha512.removePrefix("sha512-")
            else
                md.digest().joinToString("") { "%02x".format(it) } == meta.shasum
            if (!ok) throw NpmException("Checksum mismatch for ${meta.name}@${meta.version} - the download was discarded")
        } finally {
            c.disconnect()
        }
    }

    private fun verifyStaged(dir: File, meta: Meta, warnings: MutableList<String>) {
        val pj = File(dir, "package.json")
        if (!pj.exists()) throw NpmException("${meta.name} has no package.json after extraction")
        val json = try { JSONObject(pj.readText()) } catch (e: Exception) {
            throw NpmException("${meta.name} has an unreadable package.json")
        }
        if (meta.name.startsWith("@types/")) return
        val hasExports = json.has("exports")
        val main = json.optString("main")
        val entryOk = hasExports ||
            (main.isNotEmpty() && listOf("", ".js", ".json", ".cjs", "/index.js").any { File(dir, main + it).isFile }) ||
            File(dir, "index.js").isFile
        if (!entryOk) warnings.add("${meta.name} has no JavaScript entry point")
    }

    // --- tar.gz extraction (npm tarballs: gzip + ustar, optional pax / GNU long names) ---

    private fun extract(tgz: File, dest: File) {
        dest.mkdirs()
        val base = dest.canonicalFile
        GZIPInputStream(BufferedInputStream(FileInputStream(tgz))).use { gz ->
            val header = ByteArray(512)
            var longName: String? = null
            var paxPath: String? = null
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break
                var name = cstr(header, 0, 100)
                if (cstr(header, 257, 6).startsWith("ustar")) {
                    val prefix = cstr(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }
                val size = octal(header, 124, 12)
                val pad = (512 - size % 512) % 512
                when (header[156].toInt().toChar()) {
                    'x' -> { paxPath = parsePax(readBytes(gz, size)); skip(gz, pad) }
                    'g' -> skip(gz, size + pad)
                    'L' -> { longName = String(readBytes(gz, size), Charsets.UTF_8).trimEnd('\u0000'); skip(gz, pad) }
                    '0', '\u0000' -> {
                        val full = longName ?: paxPath ?: name
                        longName = null
                        paxPath = null
                        val rel = full.substringAfter('/', "") // drop the leading "package/" folder
                        if (rel.isEmpty()) {
                            skip(gz, size + pad)
                        } else {
                            val out = File(base, rel).canonicalFile
                            if (!out.path.startsWith(base.path + File.separator)) {
                                throw NpmException("Package contains an unsafe path: $full")
                            }
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { o -> copyExactly(gz, o, size) }
                            skip(gz, pad)
                        }
                    }
                    else -> { longName = null; paxPath = null; skip(gz, size + pad) }
                }
            }
        }
    }

    private fun parsePax(data: ByteArray): String? {
        for (line in String(data, Charsets.UTF_8).split('\n')) {
            val eq = line.indexOf("path=")
            if (eq >= 0) return line.substring(eq + 5)
        }
        return null
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

    private fun readBytes(s: InputStream, size: Long): ByteArray {
        if (size > 1_000_000) throw NpmException("Unexpected tar header size")
        val b = ByteArray(size.toInt())
        if (!readFully(s, b)) throw NpmException("Truncated archive")
        return b
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
            if (n < 0) throw NpmException("Truncated archive")
            o.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(i: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw NpmException("Truncated archive")
            left -= n
        }
    }
}
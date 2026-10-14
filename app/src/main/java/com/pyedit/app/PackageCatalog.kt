package com.pyedit.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** INSTALLABLE / INCOMPATIBLE (confirmed) / UNKNOWN (a lookup failed - never shown as incompatible). */
enum class Compat { INSTALLABLE, INCOMPATIBLE, UNKNOWN }

data class PackageInfo(
    val name: String,
    val version: String,
    val summary: String,
    val compat: Compat,
    val reason: String?,
    /** true when the installable build is a native Android wheel */
    val nativeBuild: Boolean
)

class PackageCatalog(private val context: Context) {

    private val userAgent = "PyEdit-Android/1.0 (package browser)"
    private val topListUrl = "https://hugovk.dev/top-pypi-packages/top-pypi-packages.min.json"
    private val topListMaxAgeMs = 7L * 24 * 60 * 60 * 1000
    private val infoCache = ConcurrentHashMap<String, PackageInfo>()
    private val gate = Semaphore(4)

    // Keep in sync with chaquopy { version = "3.11" } in build.gradle.kts
    private val pyTag = "cp311"

    companion object {
        private val nameRegex = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")
        private val sepRegex = Regex("[-_.]+")

        fun normalize(name: String): String = name.lowercase().replace(sepRegex, "-")
    }

    fun cachedInfo(name: String): PackageInfo? = infoCache[normalize(name)]

    fun looksLikeName(q: String): Boolean = nameRegex.matches(q)

    suspend fun loadNames(forceRefresh: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "top_pypi_names.txt")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < topListMaxAgeMs
        if (fresh && !forceRefresh) {
            val cached = cache.readLines().filter { it.isNotBlank() }
            if (cached.isNotEmpty()) return@withContext cached
        }
        try {
            val (code, body) = httpGet(topListUrl, 20_000)
            if (code != 200) throw IOException("Package list returned HTTP $code")
            val rows = JSONObject(body).getJSONArray("rows")
            val names = ArrayList<String>(rows.length())
            for (i in 0 until rows.length()) names.add(rows.getJSONObject(i).getString("project"))
            if (names.isEmpty()) throw IOException("Package list was empty")
            cache.writeText(names.joinToString("\n"))
            names
        } catch (e: Exception) {
            if (cache.exists()) {
                val stale = cache.readLines().filter { it.isNotBlank() }
                if (stale.isNotEmpty()) return@withContext stale
            }
            throw e
        }
    }

    fun search(query: String, names: List<String>, limit: Int = 100): List<String> {
        val q = fastNorm(query.trim())
        if (q.isEmpty()) return names.take(limit)
        val exact = ArrayList<String>()
        val starts = ArrayList<String>()
        val contains = ArrayList<String>()
        for (n in names) {
            val nn = fastNorm(n)
            when {
                nn == q -> exact.add(n)
                nn.startsWith(q) -> starts.add(n)
                nn.contains(q) -> contains.add(n)
            }
        }
        return (exact + starts + contains).take(limit)
    }

    private fun fastNorm(s: String) = s.lowercase().replace('_', '-').replace('.', '-')

    /** null = project doesn't exist; throws if PyPI itself can't be reached. */
    suspend fun info(name: String): PackageInfo? {
        val key = normalize(name)
        infoCache[key]?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val (code, body) = httpGet("https://pypi.org/pypi/$key/json", 10_000)
                when {
                    code == 404 -> null
                    code != 200 -> throw IOException("PyPI returned HTTP $code")
                    else -> parse(key, body).also { infoCache[key] = it }
                }
            }
        }
    }

    private fun parse(key: String, body: String): PackageInfo {
        val root = JSONObject(body)
        val info = root.getJSONObject("info")
        var pure = false
        val files = root.optJSONArray("urls")
        if (files != null) {
            for (i in 0 until files.length()) {
                val fn = files.getJSONObject(i).optString("filename")
                if (fn.endsWith(".whl") && isPureWheel(fn)) pure = true
            }
        }
        val name = info.optString("name")
        val version = info.optString("version")
        val summary = info.optString("summary")
        if (pure) return PackageInfo(name, version, summary, Compat.INSTALLABLE, null, false)

        // No pure wheel: look for a native Android build in Chaquopy's index.
        val (compat, reason) = androidBuildCompat(key)
        return PackageInfo(name, version, summary, compat, reason, compat == Compat.INSTALLABLE)
    }

    private fun deviceAbi(): String {
        val known = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val is64 = android.os.Process.is64Bit()
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in known && it.contains("64") == is64 }
            ?: Build.SUPPORTED_ABIS.first()
        return abi.replace('-', '_')
    }

    private fun androidBuildCompat(key: String): Pair<Compat, String?> {
        val (code, body) = try {
            httpGet("https://chaquo.com/pypi-13.1/$key/", 10_000)
        } catch (e: Exception) {
            return Pair(Compat.UNKNOWN, "Couldn't check for an Android build (network error).")
        }
        if (code == 404) return Pair(Compat.INCOMPATIBLE, "No compatible Android wheel is available.")
        if (code != 200) return Pair(Compat.UNKNOWN, "Couldn't check for an Android build (HTTP $code).")

        val abi = deviceAbi()
        val sdk = Build.VERSION.SDK_INT
        var anyForPython = false
        for (m in Regex("href=\"([^\"]+\\.whl)\"").findAll(body)) {
            val fn = m.groupValues[1].substringAfterLast('/')
            if (!fn.contains("-$pyTag-$pyTag-android_")) continue
            anyForPython = true
            val pm = Regex("android_(\\d+)_(\\w+)\\.whl$").find(fn) ?: continue
            if (pm.groupValues[2] == abi && (pm.groupValues[1].toIntOrNull() ?: 99) <= sdk) {
                return Pair(Compat.INSTALLABLE, null)
            }
        }
        return Pair(
            Compat.INCOMPATIBLE,
            if (anyForPython) "The package does not provide a compatible binary for this ABI ($abi)."
            else "No Android wheel for Python 3.11 is available."
        )
    }

    private fun isPureWheel(filename: String): Boolean {
        val parts = filename.removeSuffix(".whl").split('-')
        if (parts.size < 5) return false
        val py = parts[parts.size - 3]
        val abi = parts[parts.size - 2]
        val plat = parts[parts.size - 1]
        if (abi != "none" || plat != "any") return false
        return py.split('.').any { tag ->
            tag == "py3" ||
                Regex("py3(\\d+)").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 11 } == true
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, body)
        } finally {
            c.disconnect()
        }
    }
}
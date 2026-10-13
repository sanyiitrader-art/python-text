package com.pyedit.app

import android.content.Context
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

data class PackageInfo(
    val name: String,
    val version: String,
    val summary: String,
    /** true only if PyPI lists a pure-Python wheel this runtime could use */
    val installable: Boolean
)

class PackageCatalog(private val context: Context) {

    private val userAgent = "PyEdit-Android/1.0 (package browser)"
    private val topListUrl = "https://hugovk.dev/top-pypi-packages/top-pypi-packages.min.json"
    private val topListMaxAgeMs = 7L * 24 * 60 * 60 * 1000
    private val infoCache = ConcurrentHashMap<String, PackageInfo>()
    private val gate = Semaphore(4) // at most 4 concurrent PyPI requests

    companion object {
        private val nameRegex = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")
        private val sepRegex = Regex("[-_.]+")

        fun normalize(name: String): String = name.lowercase().replace(sepRegex, "-")
    }

    fun cachedInfo(name: String): PackageInfo? = infoCache[normalize(name)]

    fun looksLikeName(q: String): Boolean = nameRegex.matches(q)

    /** Popularity-ranked names from the monthly top-15,000 dump. Cached on disk. */
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

    /** Local, instant. Exact matches first, then prefix, then substring. */
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

    /**
     * Current metadata from PyPI. Returns null if the project doesn't exist;
     * throws on network or malformed-response errors.
     */
    suspend fun info(name: String): PackageInfo? {
        val key = normalize(name)
        infoCache[key]?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val (code, body) = httpGet("https://pypi.org/pypi/$key/json", 10_000)
                when {
                    code == 404 -> null
                    code != 200 -> throw IOException("PyPI returned HTTP $code")
                    else -> parse(body).also { infoCache[key] = it }
                }
            }
        }
    }

    private fun parse(body: String): PackageInfo {
        val root = JSONObject(body)
        val info = root.getJSONObject("info")
        var installable = false
        val files = root.optJSONArray("urls")
        if (files != null) {
            for (i in 0 until files.length()) {
                val fn = files.getJSONObject(i).optString("filename")
                if (fn.endsWith(".whl") && isPureWheel(fn)) installable = true
            }
        }
        return PackageInfo(
            name = info.optString("name"),
            version = info.optString("version"),
            summary = info.optString("summary"),
            installable = installable
        )
    }

    /** Wheel filename tags: ...-<python>-<abi>-<platform>.whl, pure = none/any. */
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
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, body)
        } finally {
            c.disconnect()
        }
    }
}
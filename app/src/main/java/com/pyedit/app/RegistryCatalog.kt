package com.pyedit.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Real search results from official registries. Browse only: nothing here installs. */
object RegistryCatalog {

    private const val UA = "PyEdit-Android/1.0 (package browser)"
    private const val JS_NOTE =
        "Browse only: the JavaScript engine has no module loader, so npm packages can't be installed."
    private const val RUBY_NOTE =
        "Browse only: there is no Ruby runtime on this device, so gems can't be installed."

    /** source: "npm", "npm-ts" or "gems". */
    suspend fun search(source: String, query: String): List<PackageInfo> = withContext(Dispatchers.IO) {
        when (source) {
            "npm" -> npm(query, null)
            "npm-ts" -> npm(query, "typescript")
            "gems" -> gems(query)
            else -> emptyList()
        }
    }

    private fun npm(query: String, keyword: String?): List<PackageInfo> {
        var text = listOfNotNull(query.trim().ifEmpty { null }, keyword?.let { "keywords:$it" }).joinToString(" ")
        if (text.isEmpty()) text = "keywords:framework" // default view: popular frameworks
        val url = "https://registry.npmjs.org/-/v1/search?size=25&text=" + URLEncoder.encode(text, "UTF-8")
        val objects = JSONObject(get(url)).getJSONArray("objects")
        val out = ArrayList<PackageInfo>()
        for (i in 0 until objects.length()) {
            val p = objects.getJSONObject(i).getJSONObject("package")
            out.add(browseOnly(p.getString("name"), p.optString("version"), p.optString("description"), JS_NOTE))
        }
        return out
    }

    private fun gems(query: String): List<PackageInfo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "https://rubygems.org/api/v1/search.json?query=" + URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(get(url))
        val out = ArrayList<PackageInfo>()
        for (i in 0 until minOf(arr.length(), 25)) {
            val g = arr.getJSONObject(i)
            out.add(browseOnly(g.getString("name"), g.optString("version"), g.optString("info"), RUBY_NOTE))
        }
        return out
    }

    private fun browseOnly(name: String, version: String, summary: String, note: String) =
        PackageInfo(
            name = name,
            version = version,
            summary = summary.trim(),
            compat = Compat.UNKNOWN,
            reason = note,
            nativeBuild = false,
            installSupported = false
        )

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code != 200) throw IOException("Registry returned HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
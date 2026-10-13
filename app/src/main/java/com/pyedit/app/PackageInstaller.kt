package com.pyedit.app

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/** Thin bridge to pyedit_pkg.py. Call from a background thread. */
class PackageInstaller(private val context: Context) {

    data class Result(val ok: Boolean, val message: String)

    private val siteDir: File
        get() = File(context.filesDir, "site-packages").apply { mkdirs() }

    private fun module(): PyObject = synchronized(PackageInstaller::class.java) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance().getModule("pyedit_pkg")
    }

    /** normalized package name -> installed version */
    fun installedVersions(): Map<String, String> {
        val map = module().callAttr("list_installed", siteDir.absolutePath).asMap()
        return map.entries.associate { PackageCatalog.normalize(it.key.toString()) to it.value.toString() }
    }

    fun install(name: String): Result {
        return try {
            val r = module().callAttr("install", name, siteDir.absolutePath).asList()
            Result(r[0].toBoolean(), r[1].toString())
        } catch (t: Throwable) {
            Result(false, "Install failed: ${t.message}")
        }
    }
}
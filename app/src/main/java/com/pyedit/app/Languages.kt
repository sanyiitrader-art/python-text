package com.pyedit.app

enum class Lang { PYTHON, JAVASCRIPT, TYPESCRIPT, LUA, RUBY, PHP, WEB, CSS }

object Languages {

    private val byExtension = mapOf(
        "py" to Lang.PYTHON,
        "js" to Lang.JAVASCRIPT, "mjs" to Lang.JAVASCRIPT, "cjs" to Lang.JAVASCRIPT,
        "ts" to Lang.TYPESCRIPT, "mts" to Lang.TYPESCRIPT, "cts" to Lang.TYPESCRIPT,
        "lua" to Lang.LUA,
        "rb" to Lang.RUBY,
        "php" to Lang.PHP,
        "html" to Lang.WEB, "htm" to Lang.WEB,
        "css" to Lang.CSS
    )

    // Every language now has a real run/preview path, so all are listed.
    private val discoverable = Lang.values().toSet()

    fun extensionOf(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

    fun forFileName(name: String): Lang? = byExtension[extensionOf(name)]

    fun isDiscoverable(name: String): Boolean =
        forFileName(name)?.let { it in discoverable } == true
}
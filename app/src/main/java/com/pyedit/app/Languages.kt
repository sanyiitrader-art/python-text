package com.pyedit.app

enum class Lang { PYTHON, JAVASCRIPT, TYPESCRIPT, LUA, RUBY, PHP, WEB, CSS }

/**
 * Single source of truth for "which extension means which language".
 * Used by the file explorer filter, rename validation and the Run button.
 */
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

    // Only languages with a real execution/preview path are listed in the
    // explorer. Ruby and PHP are recognised (so Run can explain why they
    // don't work) but deliberately not advertised.
    private val discoverable = setOf(
        Lang.PYTHON, Lang.JAVASCRIPT, Lang.TYPESCRIPT, Lang.LUA, Lang.WEB, Lang.CSS
    )

    fun extensionOf(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

    fun forFileName(name: String): Lang? = byExtension[extensionOf(name)]

    fun isDiscoverable(name: String): Boolean =
        forFileName(name)?.let { it in discoverable } == true
}
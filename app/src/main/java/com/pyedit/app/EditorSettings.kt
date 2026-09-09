package com.pyedit.app

import android.content.Context

/**
 * Spec §67/§21 "Initial editor settings foundation" — Phase 1's minimal
 * version: font size and tab/indent size, persisted via SharedPreferences.
 * Phase 3 owns "Complete Editor Settings" (search config, appearance
 * options, etc.) — this is deliberately just the foundation, not the
 * full settings screen.
 */
class EditorSettings(context: Context) {

    private val prefs = context.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)

    var fontSize: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var tabSize: Int
        get() = prefs.getInt(KEY_TAB_SIZE, DEFAULT_TAB_SIZE)
        set(value) = prefs.edit().putInt(KEY_TAB_SIZE, value).apply()

    companion object {
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_TAB_SIZE = "tab_size"
        private const val DEFAULT_FONT_SIZE = 14f
        private const val DEFAULT_TAB_SIZE = 4
    }
}
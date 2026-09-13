package com.pyedit.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pyedit_state")

/**
 * Spec §18/§63-64: persistent Recent files and persistent last active
 * file — must survive process death, not just be in-memory/session state.
 *
 * Recent entries are stored as one delimited string (no JSON library
 * needed for 3 plain-text fields — avoids adding Gson/Moshi just for
 * this). Field separator is U+0001, entry separator is newline; both are
 * control characters that can't appear in a filesystem path, so this is
 * safe without escaping.
 */
class RecentFilesStore(private val context: Context) {

    data class RecentFile(val path: String, val name: String, val lastOpenedMillis: Long)

    private object Keys {
        val RECENT = stringPreferencesKey("recent_files")
        val LAST_ACTIVE = stringPreferencesKey("last_active_file")
        val AUTOSAVE_ENABLED = stringPreferencesKey("autosave_enabled")
    }

    private val fieldSep = "\u0001"
    private val maxRecent = 20

    val recentFiles: Flow<List<RecentFile>> = context.dataStore.data.map { prefs ->
        parseRecent(prefs[Keys.RECENT] ?: "")
    }

    suspend fun addRecent(path: String, name: String) {
        context.dataStore.edit { prefs ->
            val current = parseRecent(prefs[Keys.RECENT] ?: "").toMutableList()
            current.removeAll { it.path == path } // avoid duplicate entries for the same file
            current.add(0, RecentFile(path, name, System.currentTimeMillis()))
            val trimmed = current.take(maxRecent)
            prefs[Keys.RECENT] = serializeRecent(trimmed)
        }
    }

    suspend fun clearRecent() {
        context.dataStore.edit { prefs -> prefs[Keys.RECENT] = "" }
    }

    suspend fun setLastActiveFile(path: String) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_ACTIVE] = path }
    }

    suspend fun getLastActiveFile(): String? {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.LAST_ACTIVE]?.takeIf { it.isNotBlank() }
    }

    suspend fun setAutosaveEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTOSAVE_ENABLED] = enabled.toString() }
    }

    suspend fun isAutosaveEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.AUTOSAVE_ENABLED]?.toBoolean() ?: false
    }

    private fun parseRecent(raw: String): List<RecentFile> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split(fieldSep)
            if (parts.size == 3) {
                val millis = parts[2].toLongOrNull() ?: return@mapNotNull null
                RecentFile(parts[0], parts[1], millis)
            } else null
        }
    }

    private fun serializeRecent(list: List<RecentFile>): String =
        list.joinToString("\n") { "${it.path}$fieldSep${it.name}$fieldSep${it.lastOpenedMillis}" }
}
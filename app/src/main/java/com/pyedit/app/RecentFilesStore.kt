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
 * "path" fields now hold content:// URI strings (SAF), not filesystem
 * paths — same structure as before, different meaning. Adds persisted
 * root folder tree URI, since "the workspace" is now whatever folder the
 * user picked, not a fixed app-private directory.
 */
class RecentFilesStore(private val context: Context) {

    data class RecentFile(val uriString: String, val name: String, val lastOpenedMillis: Long)

    private object Keys {
        val RECENT = stringPreferencesKey("recent_files")
        val LAST_ACTIVE = stringPreferencesKey("last_active_file")
        val AUTOSAVE_ENABLED = stringPreferencesKey("autosave_enabled")
        val ROOT_TREE_URI = stringPreferencesKey("root_tree_uri")
    }

    private val fieldSep = "\u0001"
    private val maxRecent = 20

    val recentFiles: Flow<List<RecentFile>> = context.dataStore.data.map { prefs ->
        parseRecent(prefs[Keys.RECENT] ?: "")
    }

    suspend fun addRecent(uriString: String, name: String) {
        context.dataStore.edit { prefs ->
            val current = parseRecent(prefs[Keys.RECENT] ?: "").toMutableList()
            current.removeAll { it.uriString == uriString }
            current.add(0, RecentFile(uriString, name, System.currentTimeMillis()))
            prefs[Keys.RECENT] = serializeRecent(current.take(maxRecent))
        }
    }

    suspend fun clearRecent() {
        context.dataStore.edit { prefs -> prefs[Keys.RECENT] = "" }
    }

    suspend fun setLastActiveFile(uriString: String) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_ACTIVE] = uriString }
    }

    suspend fun getLastActiveFile(): String? {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.LAST_ACTIVE]?.takeIf { it.isNotBlank() }
    }

    suspend fun setRootTreeUri(uriString: String) {
        context.dataStore.edit { prefs -> prefs[Keys.ROOT_TREE_URI] = uriString }
    }

    suspend fun getRootTreeUri(): String? {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.ROOT_TREE_URI]?.takeIf { it.isNotBlank() }
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
        list.joinToString("\n") { "${it.uriString}$fieldSep${it.name}$fieldSep${it.lastOpenedMillis}" }
}
package com.pyedit.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pyedit_state")

class RecentFilesStore(private val context: Context) {

    data class RecentFile(val uriString: String, val name: String, val lastOpenedMillis: Long)

    private object Keys {
        val RECENT = stringPreferencesKey("recent_files")
        val LAST_ACTIVE = stringPreferencesKey("last_active_file")
        val AUTOSAVE_ENABLED = stringPreferencesKey("autosave_enabled")
        val ROOT_TREE_URI = stringPreferencesKey("root_tree_uri")
        // New for Phase 3 item 1: per-file cursor position, keyed by
        // that file's own URI so switching files doesn't clobber the
        // position of whichever file you were in before.
        val CURSOR_POSITIONS = stringPreferencesKey("cursor_positions")
    }

    private val fieldSep = "\u0001"
    private val entrySep = "\n"
    private val maxRecent = 20
    private val maxCursorEntries = 50 // cap so this can't grow unbounded across a long editing history

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

    /**
     * Records where the cursor was in a given file. Called on every
     * background/pause (see MainActivity.onPause), not just on close, so
     * a process kill mid-edit still has a recent-enough position saved.
     * Safe if restoring later finds the file's content has changed
     * (line/column are clamped against the actual line count by
     * EditorController before being applied).
     */
    suspend fun setCursorPosition(uriString: String, line: Int, column: Int) {
        context.dataStore.edit { prefs ->
            val current = parseCursorPositions(prefs[Keys.CURSOR_POSITIONS] ?: "").toMutableMap()
            current[uriString] = Pair(line, column)
            // Trim oldest-inserted entries if over the cap — LinkedHashMap
            // insertion order lets us just drop from the front.
            if (current.size > maxCursorEntries) {
                val toRemove = current.keys.take(current.size - maxCursorEntries)
                toRemove.forEach { current.remove(it) }
            }
            prefs[Keys.CURSOR_POSITIONS] = serializeCursorPositions(current)
        }
    }

    suspend fun getCursorPosition(uriString: String): Pair<Int, Int>? {
        val prefs = context.dataStore.data.first()
        val map = parseCursorPositions(prefs[Keys.CURSOR_POSITIONS] ?: "")
        return map[uriString]
    }

    private fun parseRecent(raw: String): List<RecentFile> {
        if (raw.isBlank()) return emptyList()
        return raw.split(entrySep).mapNotNull { line ->
            val parts = line.split(fieldSep)
            if (parts.size == 3) {
                val millis = parts[2].toLongOrNull() ?: return@mapNotNull null
                RecentFile(parts[0], parts[1], millis)
            } else null
        }
    }

    private fun serializeRecent(list: List<RecentFile>): String =
        list.joinToString(entrySep) { "${it.uriString}$fieldSep${it.name}$fieldSep${it.lastOpenedMillis}" }

    private fun parseCursorPositions(raw: String): LinkedHashMap<String, Pair<Int, Int>> {
        val map = LinkedHashMap<String, Pair<Int, Int>>()
        if (raw.isBlank()) return map
        raw.split(entrySep).forEach { line ->
            val parts = line.split(fieldSep)
            if (parts.size == 3) {
                val l = parts[1].toIntOrNull()
                val c = parts[2].toIntOrNull()
                if (l != null && c != null) map[parts[0]] = Pair(l, c)
            }
        }
        return map
    }

    private fun serializeCursorPositions(map: Map<String, Pair<Int, Int>>): String =
        map.entries.joinToString(entrySep) { (uri, pos) -> "$uri$fieldSep${pos.first}$fieldSep${pos.second}" }
}
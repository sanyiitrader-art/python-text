package com.pyedit.app

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pyedit.app.databinding.DialogFindBinding
import com.pyedit.app.databinding.DialogGoToLineBinding
import com.pyedit.app.databinding.DialogReplaceBinding

/**
 * Step 7: Find / Replace / Go to Line. Built entirely on APIs already
 * proven in this project — Content.insert/delete (used throughout
 * PythonEditingBehavior), editor.setSelectionRegion/setSelection (used
 * for error highlighting and bracket-pairing) — no new library surface.
 *
 * Search position is computed via plain line/column <-> flat-string-index
 * conversion rather than any sora-editor search API (none was ever
 * confirmed to exist), which keeps this entirely within code I control
 * and can verify by reading.
 */
class FindReplaceController(
    private val activity: AppCompatActivity,
    private val editorController: EditorController
) {

    fun showFindDialog() {
        val dialogBinding = DialogFindBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Find")
            .setView(dialogBinding.root)
            .setPositiveButton("Find Next", null)
            .setNegativeButton("Close", null)
            .create()

        // Overriding the positive button's click listener after show()
        // (rather than passing it to setPositiveButton directly) is what
        // keeps the dialog open across repeated "Find Next" taps instead
        // of dismissing after the first one — standard AlertDialog
        // pattern for exactly this case.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = dialogBinding.editQuery.text.toString()
                if (query.isEmpty()) return@setOnClickListener
                val found = findNext(query)
                if (!found) Toast.makeText(activity, "Not found", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    fun showReplaceDialog() {
        val dialogBinding = DialogReplaceBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Replace")
            .setView(dialogBinding.root)
            .setPositiveButton("Replace", null)
            .setNeutralButton("Replace All", null)
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = dialogBinding.editQuery.text.toString()
                val replacement = dialogBinding.editReplacement.text.toString()
                if (query.isEmpty()) return@setOnClickListener
                val replaced = findAndReplaceNext(query, replacement)
                if (!replaced) Toast.makeText(activity, "Not found", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val query = dialogBinding.editQuery.text.toString()
                val replacement = dialogBinding.editReplacement.text.toString()
                if (query.isEmpty()) return@setOnClickListener
                val count = replaceAll(query, replacement)
                Toast.makeText(activity, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    fun showGoToLineDialog() {
        val dialogBinding = DialogGoToLineBinding.inflate(activity.layoutInflater)
        AlertDialog.Builder(activity)
            .setTitle("Go to Line")
            .setView(dialogBinding.root)
            .setPositiveButton("Go") { _, _ ->
                val line = dialogBinding.editLineNumber.text.toString().toIntOrNull()
                if (line != null && line > 0) {
                    editorController.moveCursorToLine(line)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Search/replace core ------------------------------------------

    private fun findNext(query: String): Boolean {
        val fullText = editorController.getText()
        if (fullText.isEmpty()) return false
        val editor = editorController.editor

        val currentIndex = flatIndexOf(fullText, editor.cursor.leftLine, editor.cursor.leftColumn)
        var foundIndex = fullText.indexOf(query, currentIndex + 1, ignoreCase = true)
        if (foundIndex == -1) {
            foundIndex = fullText.indexOf(query, 0, ignoreCase = true) // wrap around
        }
        if (foundIndex == -1) return false

        selectRange(foundIndex, foundIndex + query.length, fullText)
        return true
    }

    private fun findAndReplaceNext(query: String, replacement: String): Boolean {
        val fullText = editorController.getText()
        if (fullText.isEmpty()) return false
        val editor = editorController.editor

        val currentIndex = flatIndexOf(fullText, editor.cursor.leftLine, editor.cursor.leftColumn)
        var foundIndex = fullText.indexOf(query, currentIndex, ignoreCase = true)
        if (foundIndex == -1) {
            foundIndex = fullText.indexOf(query, 0, ignoreCase = true)
        }
        if (foundIndex == -1) return false

        val (startLine, startCol) = lineColFromIndex(fullText, foundIndex)
        val (endLine, endCol) = lineColFromIndex(fullText, foundIndex + query.length)
        try {
            editor.text.delete(startLine, startCol, endLine, endCol)
            editor.text.insert(startLine, startCol, replacement)
            editor.setSelectionRegion(startLine, startCol, startLine, startCol + replacement.length)
        } catch (t: Throwable) {
            return false
        }
        return true
    }

    private fun replaceAll(query: String, replacement: String): Int {
        val fullText = editorController.getText()
        if (fullText.isEmpty()) return 0

        val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        val count = regex.findAll(fullText).count()
        if (count == 0) return 0

        val newText = fullText.replace(regex, Regex.escapeReplacement(replacement))
        editorController.replaceAllText(newText)
        return count
    }

    private fun selectRange(startIndex: Int, endIndex: Int, fullText: String) {
        val (startLine, startCol) = lineColFromIndex(fullText, startIndex)
        val (endLine, endCol) = lineColFromIndex(fullText, endIndex)
        try {
            editorController.editor.setSelectionRegion(startLine, startCol, endLine, endCol)
        } catch (t: Throwable) { /* best-effort */ }
    }

    private fun flatIndexOf(text: String, line: Int, col: Int): Int {
        var index = 0
        var currentLine = 0
        var searchStart = 0
        while (currentLine < line) {
            val nextNewline = text.indexOf('\n', searchStart)
            if (nextNewline == -1) break
            searchStart = nextNewline + 1
            currentLine++
        }
        index = searchStart + col
        return index.coerceIn(0, text.length)
    }

    private fun lineColFromIndex(text: String, index: Int): Pair<Int, Int> {
        val clamped = index.coerceIn(0, text.length)
        var line = 0
        var searchStart = 0
        while (true) {
            val nextNewline = text.indexOf('\n', searchStart)
            if (nextNewline == -1 || nextNewline >= clamped) {
                return Pair(line, clamped - searchStart)
            }
            line++
            searchStart = nextNewline + 1
        }
    }
}
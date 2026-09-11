package com.pyedit.app

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor

class PythonEditingBehavior(private val editor: CodeEditor) : ContentListener {

    private val autoPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    private val closers = setOf(')', ']', '}', '"', '\'')
    private val dedentKeywords = setOf("elif", "else", "except", "finally", "case")

    private var isProgrammaticEdit = false

    /**
     * Tracks the most recent run of spaces WE inserted (via colon-triggered
     * auto-indent, or the toolbar's Indent button) so backspace can treat
     * it differently from spaces the user typed manually one keystroke at
     * a time. "smart backspace = smart indentation" — only OUR insertions
     * get the 4-at-a-time treatment; everything else is 1-at-a-time,
     * matching how many keystrokes actually created it.
     */
    private data class SmartRegion(val line: Int, var endColumn: Int, var remainingLevels: Int)
    private var smartRegion: SmartRegion? = null

    fun attach() {
        editor.text.addContentListener(this)
    }

    override fun afterInsert(
        content: Content,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        insertedContent: CharSequence
    ) {
        if (isProgrammaticEdit) return

        // Toolbar's Indent button sends its whole "    " as one commitText
        // call (one afterInsert event with length 4), whereas actual
        // typing always arrives one character per event — so this
        // reliably identifies toolbar-driven indentation, anywhere on a
        // line, including after existing text.
        if (insertedContent.length == 4 && insertedContent.all { it == ' ' }) {
            markSmartRegion(endLine, endColumn - 4, endColumn, 1)
            return
        }

        if (insertedContent.length != 1) return
        val ch = insertedContent[0]

        if (ch in closers) {
            val lineText = content.getLineString(endLine)
            if (endColumn < lineText.length && lineText[endColumn] == ch) {
                programmaticDelete(content, endLine, endColumn, endColumn + 1)
                return
            }
        }

        autoPairs[ch]?.let { closer ->
            programmaticInsert(content, endLine, endColumn, closer.toString())
            try {
                editor.setSelection(endLine, endColumn)
            } catch (t: Throwable) { /* best-effort cursor placement */ }
        }

        if (ch == '\n') {
            val capturedLineIndex = endLine
            editor.post {
                normalizeIndentForNewLine(content, capturedLineIndex)
            }
        }
    }

    private fun normalizeIndentForNewLine(content: Content, newLineIndex: Int) {
        if (newLineIndex == 0 || newLineIndex >= content.lineCount) return

        val previousLine = content.getLineString(newLineIndex - 1)
        val previousIndent = previousLine.takeWhile { it == ' ' }
        val previousTrimmed = previousLine.trim()
        val firstWord = previousTrimmed.takeWhile { it.isLetter() }

        val targetIndent = when {
            previousTrimmed.endsWith(":") -> previousIndent + "    "
            firstWord in dedentKeywords && previousIndent.length >= 4 ->
                previousIndent.dropLast(4)
            else -> previousIndent
        }

        val currentLineText = content.getLineString(newLineIndex)
        val currentLeading = currentLineText.takeWhile { it == ' ' }

        if (currentLeading != targetIndent) {
            val wasAdding = targetIndent.length > currentLeading.length
            if (currentLeading.isNotEmpty()) {
                programmaticDelete(content, newLineIndex, 0, currentLeading.length)
            }
            if (targetIndent.isNotEmpty()) {
                programmaticInsert(content, newLineIndex, 0, targetIndent)
            }
            try {
                editor.setSelection(newLineIndex, targetIndent.length)
            } catch (t: Throwable) { /* best-effort cursor placement */ }

            if (wasAdding) {
                val levels = maxOf(1, (targetIndent.length - currentLeading.length) / 4)
                markSmartRegion(newLineIndex, currentLeading.length, targetIndent.length, levels)
            } else if (smartRegion?.line == newLineIndex) {
                smartRegion = null
            }
        }
    }

    /**
     * Registers (or extends) the tracked smart-indent region. Extending
     * matters for e.g. three consecutive toolbar Indent taps — each one
     * should count as its own removable level on backspace, not get
     * collapsed into "one big block" or lost after the first press.
     */
    private fun markSmartRegion(line: Int, insertionStart: Int, newEnd: Int, levelsAdded: Int) {
        val existing = smartRegion
        if (existing != null && existing.line == line && existing.endColumn == insertionStart) {
            existing.endColumn = newEnd
            existing.remainingLevels += levelsAdded
        } else {
            smartRegion = SmartRegion(line, newEnd, levelsAdded)
        }
    }

    override fun beforeReplace(content: Content) {
        // No pre-processing needed.
    }

    override fun afterDelete(
        content: Content,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        deletedContent: CharSequence
    ) {
        if (isProgrammaticEdit) return
        val deletedText = deletedContent.toString()

        // Cross-line delete: keyboard merged an indentation-only line
        // into the previous one in a single backspace. Split that back
        // apart so the first backspace only cancels the indentation,
        // the second one actually merges the lines.
        if (startLine != endLine) {
            if (deletedText.length <= 1) return
            if (deletedText[0] != '\n') return
            if (deletedText.drop(1).any { it != ' ' }) return
            programmaticInsert(content, startLine, startColumn, "\n")
            return
        }

        if (deletedText.isEmpty() || deletedText.any { it != ' ' }) {
            if (smartRegion?.line == startLine) smartRegion = null
            return
        }

        val region = smartRegion
        val regionMatches = region != null &&
            region.line == startLine &&
            region.endColumn == startColumn + deletedText.length

        if (regionMatches) {
            // Normalize to "exactly one 4-space level removed", regardless
            // of how many characters the keyboard's own event removed.
            val desiredNewEnd = region!!.endColumn - 4
            val actualNewEnd = startColumn
            val diff = desiredNewEnd - actualNewEnd
            if (diff > 0) programmaticInsert(content, startLine, startColumn, " ".repeat(diff))
            else if (diff < 0) programmaticDelete(content, startLine, desiredNewEnd, actualNewEnd)

            region.remainingLevels -= 1
            region.endColumn = maxOf(desiredNewEnd, 0)
            if (region.remainingLevels <= 0) smartRegion = null
            return
        }

        // Not a tracked smart region — only step in for genuinely manual
        // leading whitespace (nothing but spaces before the cursor), and
        // only to normalize to "exactly one space removed", matching the
        // number of keystrokes the user actually made. Whitespace with
        // real text before it on the line is left completely alone —
        // that was already behaving correctly.
        val lineText = content.getLineString(startLine)
        val isLeading = lineText.substring(0, startColumn).all { it == ' ' }
        if (!isLeading) return

        val originalLength = startColumn + deletedText.length
        val desiredRemaining = maxOf(0, originalLength - 1)
        val actualRemaining = startColumn
        val diff = desiredRemaining - actualRemaining
        if (diff > 0) programmaticInsert(content, startLine, startColumn, " ".repeat(diff))
        else if (diff < 0) programmaticDelete(content, startLine, desiredRemaining, actualRemaining)
    }

    private fun programmaticInsert(content: Content, line: Int, column: Int, text: String) {
        isProgrammaticEdit = true
        try {
            content.insert(line, column, text)
        } finally {
            isProgrammaticEdit = false
        }
    }

    private fun programmaticDelete(content: Content, line: Int, startCol: Int, endCol: Int) {
        isProgrammaticEdit = true
        try {
            content.delete(line, startCol, line, endCol)
        } finally {
            isProgrammaticEdit = false
        }
    }
}
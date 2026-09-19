package com.pyedit.app

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor

class PythonEditingBehavior(private val editor: CodeEditor) : ContentListener {

    private val autoPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    private val closers = setOf(')', ']', '}', '"', '\'')
    private val dedentKeywords = setOf("elif", "else", "except", "finally", "case")

    private var isProgrammaticEdit = false

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

        // Toolbar Indent button still inserts its 4 spaces exactly as
        // before — the ONLY thing removed here is the now-unnecessary
        // smart-region bookkeeping that was the source of both bugs.
        if (insertedContent.length == 4 && insertedContent.all { it == ' ' }) {
            return
        }

        if (insertedContent.contains('\n')) {
            val capturedLineIndex = endLine
            normalizeIndentForNewLine(content, capturedLineIndex)
            editor.post {
                normalizeIndentForNewLine(content, capturedLineIndex)
            }
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
            if (currentLeading.isNotEmpty()) {
                programmaticDelete(content, newLineIndex, 0, currentLeading.length)
            }
            if (targetIndent.isNotEmpty()) {
                programmaticInsert(content, newLineIndex, 0, targetIndent)
            }
            try {
                editor.setSelection(newLineIndex, targetIndent.length)
            } catch (t: Throwable) { /* best-effort cursor placement */ }
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

        // Cross-line: keyboard merged an indentation-only line into the
        // previous one in one event. Split it back apart so the first
        // backspace only cancels one indent level, never merges outright.
        if (startLine != endLine) {
            if (deletedText.length <= 1) return
            if (deletedText[0] != '\n') return
            val wsAfterNewline = deletedText.drop(1)
            if (wsAfterNewline.any { it != ' ' }) return
            if (wsAfterNewline.isEmpty()) return

            val remaining = snapDownOneLevel(wsAfterNewline.length)
            programmaticInsert(content, startLine, startColumn, "\n" + " ".repeat(remaining))
            try {
                editor.setSelection(startLine + 1, remaining)
            } catch (t: Throwable) { /* best-effort */ }
            return
        }

        // Same-line: this is the actual fix. No history/origin tracking
        // at all — purely "is the cursor currently inside the leading
        // whitespace of this line?" If the deleted text was space(s) AND
        // everything still remaining before the cursor is also just
        // spaces, we're in the leading-indent region regardless of
        // whether that whitespace came from a colon-triggered auto-
        // indent three lines ago, a continued (non-colon) line, or the
        // toolbar button — all of which were exactly the cases the old
        // per-line tracking lost.
        if (deletedText.isEmpty() || deletedText.any { it != ' ' }) return

        val lineText = content.getLineString(startLine)
        val prefixBeforeCursor = lineText.substring(0, startColumn)
        if (prefixBeforeCursor.any { it != ' ' }) return // real text before cursor — leave default behavior alone

        val originalLength = startColumn + deletedText.length
        val desiredRemaining = snapDownOneLevel(originalLength)
        val actualRemaining = startColumn
        val diff = desiredRemaining - actualRemaining
        if (diff > 0) programmaticInsert(content, startLine, startColumn, " ".repeat(diff))
        else if (diff < 0) programmaticDelete(content, startLine, desiredRemaining, actualRemaining)
    }

    /** Removes one 4-space level, snapping down to the nearest lower
     * 4-boundary if the current length isn't already a clean multiple
     * (e.g. 6 -> 4, not 2), and never going below 0. */
    private fun snapDownOneLevel(currentLength: Int): Int {
        if (currentLength <= 4) return 0
        return if (currentLength % 4 == 0) currentLength - 4 else (currentLength / 4) * 4
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
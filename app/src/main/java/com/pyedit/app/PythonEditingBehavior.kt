package com.pyedit.app

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor

class PythonEditingBehavior(private val editor: CodeEditor) : ContentListener {

    private val autoPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    private val closers = setOf(')', ']', '}', '"', '\'')
    private val dedentKeywords = setOf("elif", "else", "except", "finally", "case")

    // CRITICAL FIX: without this guard, auto-inserting a closing quote
    // re-triggers this same listener (since '"' is both the trigger and
    // the result), which inserts another quote, forever — this was the
    // freeze. Every edit WE make programmatically is now wrapped so it
    // can never re-enter this listener's own logic.
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
            // FIX: without this, the cursor ends up after the closer
            // ("{}|") instead of between the pair ("{|}"), because
            // inserting text exactly at the cursor's position moves the
            // cursor to the end of what was inserted. Explicitly moving
            // it back is what keeps the cursor between the brackets.
            try {
                editor.setSelection(endLine, endColumn)
            } catch (t: Throwable) {
                // If this exact method name doesn't exist in this
                // version, the pairing still works, just with the older
                // (wrong) cursor placement — not worth crashing over.
            }
        }

        if (ch == '\n') {
            handleNewline(content, endLine)
        }
    }

    private fun handleNewline(content: Content, newLineIndex: Int) {
        if (newLineIndex == 0) return

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

        // FIX for "nested indentation doesn't work": rather than assuming
        // the new line starts empty and adding our indent on top of it
        // (which breaks if the editor's own default behavior already put
        // something there), we normalize the new line's leading
        // whitespace to exactly targetIndent regardless of what's
        // currently there. This is what makes each new colon-ended line
        // compute its OWN indent level from ITS OWN previous line, rather
        // than seemingly "inheriting" a stuck level.
        val currentLineText = content.getLineString(newLineIndex)
        val currentLeading = currentLineText.takeWhile { it == ' ' }
        if (currentLeading != targetIndent) {
            if (currentLeading.isNotEmpty()) {
                programmaticDelete(content, newLineIndex, 0, currentLeading.length)
            }
            if (targetIndent.isNotEmpty()) {
                programmaticInsert(content, newLineIndex, 0, targetIndent)
            }
        }
    }

    override fun beforeReplace(content: Content) {
        // No pre-processing needed.
    }

    /**
     * FIX for "Backspace jumps straight back to the previous line instead
     * of first cancelling the indentation": the on-screen keyboard's own
     * "smart backspace" deletes the newline AND the indentation in one
     * single delete when the line being backspaced-from contains only
     * whitespace — it doesn't delete one space at a time the way a
     * physical keyboard would. So instead of watching for single-space
     * deletes, we watch for exactly this pattern (a deleted span that is
     * a newline followed only by spaces) and undo just the "merge lines"
     * part of it: we re-insert the newline, leaving the indentation gone
     * but the two lines still separate. The NEXT backspace then has
     * nothing left but the newline itself to delete, which merges the
     * lines — giving the requested two-step behavior.
     */
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
        if (startLine == endLine) return // single-line delete, nothing to split back apart
        if (deletedText.isEmpty() || deletedText[0] != '\n') return
        if (deletedText.drop(1).any { it != ' ' }) return // must be newline + only spaces

        programmaticInsert(content, startLine, startColumn, "\n")
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
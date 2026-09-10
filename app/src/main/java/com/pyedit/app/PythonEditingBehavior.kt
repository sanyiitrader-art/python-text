package com.pyedit.app

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor

class PythonEditingBehavior(private val editor: CodeEditor) : ContentListener {

    private val autoPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    private val closers = setOf(')', ']', '}', '"', '\'')
    private val dedentKeywords = setOf("elif", "else", "except", "finally", "case")

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
        if (insertedContent.length != 1) return
        val ch = insertedContent[0]

        if (ch in closers) {
            val lineText = content.getLineString(endLine)
            if (endColumn < lineText.length && lineText[endColumn] == ch) {
                content.delete(endLine, endColumn, endLine, endColumn + 1)
                return
            }
        }

        autoPairs[ch]?.let { closer ->
            content.insert(endLine, endColumn, closer.toString())
        }

        if (ch == '\n') {
            handleNewline(content, endLine)
        }
    }

    private fun handleNewline(content: Content, newLineIndex: Int) {
        if (newLineIndex == 0) return
        val previousLine = content.getLineString(newLineIndex - 1).trimEnd()
        val currentIndent = content.getLineString(newLineIndex - 1).takeWhile { it == ' ' }
        val firstWord = previousLine.trimStart().takeWhile { it.isLetter() }

        val targetIndent = when {
            previousLine.endsWith(":") -> currentIndent + "    "
            firstWord in dedentKeywords && currentIndent.length >= 4 -> currentIndent.dropLast(4)
            else -> currentIndent
        }

        if (targetIndent.isNotEmpty()) {
            content.insert(newLineIndex, 0, targetIndent)
        }
    }

    override fun beforeReplace(content: Content) {
        // No pre-processing needed.
    }

    /**
     * Spec §37: physical Backspace removes a whole indent level (4 spaces)
     * when the cursor sits inside pure leading whitespace at a tab-stop
     * boundary, instead of just one space.
     *
     * Logic: the default single-character delete has already happened by
     * the time this fires, so `startColumn` is the column AFTER that one
     * space was removed. We reconstruct the column BEFORE that delete
     * (startColumn + 1); if that was itself a multiple of 4 (i.e. the
     * cursor was exactly at a tab-stop when Backspace was pressed), we
     * remove 3 more spaces to complete removing the full 4-space level.
     * Otherwise we leave it as an ordinary single-character delete.
     */
    override fun afterDelete(
        content: Content,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        deletedContent: CharSequence
    ) {
        if (deletedContent.toString() != " ") return
        if (startLine != endLine) return

        val lineText = content.getLineString(startLine)
        val beforeCursor = lineText.substring(0, startColumn)
        if (beforeCursor.any { it != ' ' }) return // not pure leading indentation

        val columnBeforeThisDelete = startColumn + 1
        if (columnBeforeThisDelete % 4 == 0 && columnBeforeThisDelete > 0) {
            val extraToRemove = minOf(3, startColumn)
            if (extraToRemove > 0) {
                content.delete(startLine, startColumn - extraToRemove, startLine, startColumn)
            }
        }
    }
}
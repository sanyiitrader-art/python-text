package com.pyedit.app

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor

class PythonEditingBehavior(private val editor: CodeEditor) : ContentListener {

    private val autoPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    private val closers = setOf(')', ']', '}', '"', '\'')
    private val dedentKeywords = setOf("elif", "else", "except", "finally", "case")

    private var isProgrammaticEdit = false

    private data class SmartRegion(val line: Int, var endColumn: Int, var remainingLevels: Int)
    private var smartRegion: SmartRegion? = null

    // Debounce for backspace bursts: the keyboard's own "smart delete" for
    // a run of spaces appears to issue several separate delete() calls in
    // rapid succession for a SINGLE physical backspace press, not one call.
    // Without coalescing these, each call was independently removing one
    // indent level, so one press was cancelling multiple levels at once.
    private var lastBackspaceAdjustTimeMs = 0L
    private val backspaceBurstWindowMs = 150L

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

        if (insertedContent.length == 4 && insertedContent.all { it == ' ' }) {
            markSmartRegion(endLine, endColumn - 4, endColumn, 1)
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
            val now = System.currentTimeMillis()
            if (now - lastBackspaceAdjustTimeMs < backspaceBurstWindowMs) {
                // Part of the same physical backspace press as the last
                // event we processed — fully undo this chunk instead of
                // removing another level on top of it.
                programmaticInsert(content, startLine, startColumn, deletedText)
                return
            }
            lastBackspaceAdjustTimeMs = now

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

        val lineText = content.getLineString(startLine)
        val isLeading = lineText.substring(0, startColumn).all { it == ' ' }
        if (!isLeading) return

        val now = System.currentTimeMillis()
        if (now - lastBackspaceAdjustTimeMs < backspaceBurstWindowMs) {
            programmaticInsert(content, startLine, startColumn, deletedText)
            return
        }
        lastBackspaceAdjustTimeMs = now

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
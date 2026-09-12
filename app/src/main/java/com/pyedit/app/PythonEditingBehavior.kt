package com.pyedit.app

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor

class PythonEditingBehavior(private val editor: CodeEditor) : ContentListener {

    private val autoPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    private val closers = setOf(')', ']', '}', '"', '\'')
    private val dedentKeywords = setOf("elif", "else", "except", "finally", "case")

    private var isProgrammaticEdit = false

    // FIX: previously tracked a countdown ("remainingLevels") that got
    // exhausted after one correction, forgetting the rest of that line's
    // smart-indented whitespace was still smart. Now this just remembers
    // "this line's leading whitespace, up to this length, is smart" —
    // it persists across as many backspaces as needed until the line's
    // indentation reaches zero, whether it was built by one Enter, several
    // nested ones, or toolbar taps.
    private data class SmartRegion(val line: Int, var endColumn: Int)
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

        if (insertedContent.length == 4 && insertedContent.all { it == ' ' }) {
            markSmartRegion(endLine, endColumn)
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

            // Whole resulting indentation on this line is smart now,
            // regardless of what portion was added just this keystroke.
            if (targetIndent.isNotEmpty()) {
                markSmartRegion(newLineIndex, targetIndent.length)
            } else if (smartRegion?.line == newLineIndex) {
                smartRegion = null
            }
        }
    }

    private fun markSmartRegion(line: Int, endColumn: Int) {
        val existing = smartRegion
        if (existing != null && existing.line == line) {
            existing.endColumn = endColumn
        } else {
            smartRegion = SmartRegion(line, endColumn)
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
            val wsAfterNewline = deletedText.drop(1)
            if (wsAfterNewline.any { it != ' ' }) return
            if (wsAfterNewline.isEmpty()) return

            val region = smartRegion
            val isTrackedRegion = region != null &&
                region.line == endLine &&
                region.endColumn == wsAfterNewline.length
            val stepSize = if (isTrackedRegion) 4 else 1
            val remaining = maxOf(0, wsAfterNewline.length - stepSize)

            programmaticInsert(content, startLine, startColumn, "\n" + " ".repeat(remaining))
            try {
                editor.setSelection(startLine + 1, remaining)
            } catch (t: Throwable) { /* best-effort cursor placement */ }

            if (isTrackedRegion) {
                if (remaining <= 0) smartRegion = null
                else region!!.endColumn = remaining
            }
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
            val desiredNewEnd = maxOf(0, region!!.endColumn - 4)
            val actualNewEnd = startColumn
            val diff = desiredNewEnd - actualNewEnd
            if (diff > 0) programmaticInsert(content, startLine, startColumn, " ".repeat(diff))
            else if (diff < 0) programmaticDelete(content, startLine, desiredNewEnd, actualNewEnd)

            if (desiredNewEnd <= 0) smartRegion = null
            else region.endColumn = desiredNewEnd
            return
        }

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
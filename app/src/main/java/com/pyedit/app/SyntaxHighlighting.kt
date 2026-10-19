package com.pyedit.app

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme as Scheme

enum class HighlightKind { PYTHON, JAVASCRIPT, TYPESCRIPT, LUA, HTML, CSS }

/** Maps a file extension to a highlighter. Null = no highlighting for that type. */
object SyntaxHighlighting {

    fun kindForExtension(ext: String): HighlightKind? = when (ext.lowercase()) {
        "py" -> HighlightKind.PYTHON
        "js", "mjs", "cjs" -> HighlightKind.JAVASCRIPT
        "ts", "mts", "cts" -> HighlightKind.TYPESCRIPT
        "lua" -> HighlightKind.LUA
        "html", "htm" -> HighlightKind.HTML
        "css" -> HighlightKind.CSS
        else -> null
    }

    fun languageFor(ext: String): Language? = kindForExtension(ext)?.let { HighlightLanguage(it) }
}

/** Behaves exactly like the default (empty) language, except it supplies colors. */
class HighlightLanguage(kind: HighlightKind) : EmptyLanguage() {
    private val manager = HighlightAnalyzer(kind)
    override fun getAnalyzeManager(): AnalyzeManager = manager
}

// --- token kinds and their styles --------------------------------------------

private const val K_KEYWORD = 1
private const val K_COMMENT = 2
private const val K_STRING = 3
private const val K_NUMBER = 4
private const val K_FUNC = 5
private const val K_CLASS = 6
private const val K_OP = 7
private const val K_TAG = 8
private const val K_ATTR = 9
private const val K_ANNOT = 10

/** Above this size highlighting is skipped to protect low-end devices. */
private const val MAX_CHARS = 250_000

private val NORMAL = TextStyle.makeStyle(Scheme.TEXT_NORMAL)

private val STYLES = LongArray(11).also {
    it[K_KEYWORD] = TextStyle.makeStyle(Scheme.KEYWORD)
    it[K_COMMENT] = TextStyle.makeStyle(Scheme.COMMENT, 0, false, true, false) // italic
    it[K_STRING] = TextStyle.makeStyle(Scheme.ATTRIBUTE_VALUE)
    it[K_NUMBER] = TextStyle.makeStyle(Scheme.LITERAL)
    it[K_FUNC] = TextStyle.makeStyle(Scheme.FUNCTION_NAME)
    it[K_CLASS] = TextStyle.makeStyle(Scheme.IDENTIFIER_NAME)
    it[K_OP] = TextStyle.makeStyle(Scheme.OPERATOR)
    it[K_TAG] = TextStyle.makeStyle(Scheme.HTML_TAG)
    it[K_ATTR] = TextStyle.makeStyle(Scheme.ATTRIBUTE_NAME)
    it[K_ANNOT] = TextStyle.makeStyle(Scheme.ANNOTATION)
}

// --- per-language rules --------------------------------------------------------

private class Cfg(
    val keywords: Set<String>,
    val constants: Set<String>,
    val lineComments: List<String>,
    val blockStart: String?,
    val blockEnd: String?,
    val backtick: Boolean,
    val triple: Boolean,
    val decorators: Boolean,
    val lua: Boolean,
    val functionWords: Set<String>,
    val classWords: Set<String>,
    val soft: Set<String>
)

private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

private val JS_WORDS = "break case catch class const continue debugger default delete do else export extends " +
    "finally for function if import in instanceof let new of return static super switch this throw try " +
    "typeof var void while with yield async await"

private val PY = Cfg(
    keywords = words(
        "and as assert async await break class continue def del elif else except finally for from global if " +
            "import in is lambda nonlocal not or pass raise return try while with yield"
    ),
    constants = words("True False None"),
    lineComments = listOf("#"), blockStart = null, blockEnd = null,
    backtick = false, triple = true, decorators = true, lua = false,
    functionWords = words("def"), classWords = words("class"), soft = words("match case")
)

private val JS = Cfg(
    keywords = words(JS_WORDS),
    constants = words("true false null undefined NaN Infinity"),
    lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/",
    backtick = true, triple = false, decorators = false, lua = false,
    functionWords = words("function"), classWords = words("class"), soft = emptySet()
)

private val TS = Cfg(
    keywords = words(
        "$JS_WORDS abstract as declare enum implements interface keyof namespace private protected public " +
            "readonly satisfies type"
    ),
    constants = words("true false null undefined NaN Infinity"),
    lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/",
    backtick = true, triple = false, decorators = true, lua = false,
    functionWords = words("function"), classWords = words("class interface enum namespace"), soft = emptySet()
)

private val LUA = Cfg(
    keywords = words(
        "and break do else elseif end for function goto if in local not or repeat return then until while"
    ),
    constants = words("true false nil"),
    lineComments = emptyList(), blockStart = null, blockEnd = null,
    backtick = false, triple = false, decorators = false, lua = true,
    functionWords = words("function"), classWords = emptySet(), soft = emptySet()
)

private const val OPS = "+-*/%=<>!&|^~?"
private val PY_PREFIXES = setOf("r", "b", "f", "u", "rb", "br", "fr", "rf", "ur")

// --- lexer -----------------------------------------------------------------------

private class Lexer(private val t: CharSequence, private val cancel: () -> Boolean) {

    var n = 0
    var ts = IntArray(2048)
    var te = IntArray(2048)
    var tk = ByteArray(2048)
    var stopped = false
    private var tick = 0

    private fun add(s: Int, e: Int, k: Int) {
        if (e <= s) return
        if (n == ts.size) {
            ts = ts.copyOf(n * 2); te = te.copyOf(n * 2); tk = tk.copyOf(n * 2)
        }
        ts[n] = s; te[n] = e; tk[n] = k.toByte(); n++
    }

    /** true = stop now (a newer edit made this run obsolete). */
    private fun stop(): Boolean {
        tick++
        if ((tick and 255) == 0 && cancel()) stopped = true
        return stopped
    }

    private fun lineEnd(i: Int, to: Int): Int {
        var j = i
        while (j < to && t[j] != '\n') j++
        return j
    }

    private fun nextNonSpace(i: Int, to: Int): Char {
        var j = i
        while (j < to && (t[j] == ' ' || t[j] == '\t')) j++
        return if (j < to) t[j] else '\u0000'
    }

    private fun prevNonSpace(i: Int, from: Int): Char {
        var j = i - 1
        while (j >= from && (t[j] == ' ' || t[j] == '\t')) j--
        return if (j >= from) t[j] else '\u0000'
    }

    private fun longBracketEnd(i: Int, to: Int): Int {
        if (i >= to || t[i] != '[') return -1
        var j = i + 1
        var level = 0
        while (j < to && t[j] == '=') { level++; j++ }
        if (j >= to || t[j] != '[') return -1
        val closing = "]" + "=".repeat(level) + "]"
        val idx = t.indexOf(closing, j + 1)
        return if (idx < 0) to else minOf(idx + closing.length, to)
    }

    private fun scanString(i: Int, to: Int, c: Cfg, q: Char): Int {
        if (c.triple && i + 2 < to && t[i + 1] == q && t[i + 2] == q) {
            var j = i + 3
            while (j < to) {
                if (t[j] == '\\') j += 2
                else if (t[j] == q && j + 2 < to + 0 && t[j + 1] == q && t[j + 2] == q) return j + 3
                else j++
            }
            return to
        }
        var j = i + 1
        while (j < to) {
            val d = t[j]
            if (d == '\\') j += 2
            else if (d == q) return minOf(j + 1, to)
            else if (d == '\n' && q != '`') return j
            else j++
        }
        return to
    }

    private fun isSoftKeyword(i: Int, j: Int, from: Int, to: Int): Boolean {
        var a = i - 1
        while (a >= from && (t[a] == ' ' || t[a] == '\t')) a--
        if (a >= from && t[a] != '\n') return false
        var e = j
        while (e < to && t[e] != '\n') e++
        while (e > j && (t[e - 1] == ' ' || t[e - 1] == '\t' || t[e - 1] == '\r')) e--
        return e > j && t[e - 1] == ':'
    }

    // Python / JavaScript / TypeScript / Lua
    fun lexCode(c: Cfg, from: Int, to: Int) {
        var i = from
        var expectName = 0 // 1 = function name next, 2 = class name next
        while (i < to) {
            if (stop()) return
            val ch = t[i]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') { i++; continue }
            val idStart = ch.isLetter() || ch == '_' || ch == '$'
            if (!idStart) expectName = 0

            if (c.lua) {
                if (ch == '-' && i + 1 < to && t[i + 1] == '-') {
                    val lb = longBracketEnd(i + 2, to)
                    val end = if (lb >= 0) lb else lineEnd(i, to)
                    add(i, end, K_COMMENT); i = end; continue
                }
                if (ch == '[') {
                    val lb = longBracketEnd(i, to)
                    if (lb >= 0) { add(i, lb, K_STRING); i = lb; continue }
                }
            }

            var consumed = false
            for (lc in c.lineComments) {
                if (t.startsWith(lc, i)) {
                    val end = lineEnd(i, to)
                    add(i, end, K_COMMENT); i = end; consumed = true
                    break
                }
            }
            if (consumed) continue

            val bs = c.blockStart
            val be = c.blockEnd
            if (bs != null && be != null && t.startsWith(bs, i)) {
                val close = t.indexOf(be, i + bs.length)
                val end = if (close < 0 || close + be.length > to) to else close + be.length
                add(i, end, K_COMMENT); i = end; continue
            }

            if (ch == '"' || ch == '\'' || (ch == '`' && c.backtick)) {
                val end = scanString(i, to, c, ch)
                add(i, end, K_STRING); i = end; continue
            }

            if (ch.isDigit() || (ch == '.' && i + 1 < to && t[i + 1].isDigit())) {
                var j = i + 1
                val hex = ch == '0' && j < to && (t[j] == 'x' || t[j] == 'X')
                while (j < to) {
                    val d = t[j]
                    if (d.isLetterOrDigit() || d == '_') j++
                    else if (d == '.' && j + 1 < to && t[j + 1].isDigit()) j++
                    else if ((d == '+' || d == '-') && !hex && (t[j - 1] == 'e' || t[j - 1] == 'E')) j++
                    else break
                }
                add(i, j, K_NUMBER); i = j; continue
            }

            if (idStart) {
                var j = i + 1
                while (j < to && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '$')) j++
                if (c.triple && j < to && (t[j] == '"' || t[j] == '\'') && j - i <= 2 &&
                    t.substring(i, j).lowercase() in PY_PREFIXES
                ) {
                    val end = scanString(j, to, c, t[j])
                    add(i, end, K_STRING); i = end; continue
                }
                val word = t.substring(i, j)
                val afterDot = prevNonSpace(i, from) == '.'
                var kind = 0
                when {
                    expectName != 0 -> { kind = if (expectName == 2) K_CLASS else K_FUNC; expectName = 0 }
                    !afterDot && word in c.keywords -> {
                        kind = K_KEYWORD
                        expectName = when (word) {
                            in c.functionWords -> 1
                            in c.classWords -> 2
                            else -> 0
                        }
                    }
                    !afterDot && word in c.constants -> kind = K_NUMBER
                    !afterDot && word in c.soft && isSoftKeyword(i, j, from, to) -> kind = K_KEYWORD
                    nextNonSpace(j, to) == '(' -> kind = K_FUNC
                }
                if (kind != 0) add(i, j, kind)
                i = j; continue
            }

            if (ch == '@' && c.decorators && i + 1 < to && (t[i + 1].isLetter() || t[i + 1] == '_')) {
                var j = i + 1
                while (j < to && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '.')) j++
                add(i, j, K_ANNOT); i = j; continue
            }

            if (OPS.indexOf(ch) >= 0) {
                var j = i + 1
                while (j < to && OPS.indexOf(t[j]) >= 0) j++
                add(i, j, K_OP); i = j; continue
            }
            i++
        }
    }

    // CSS
    private fun cssQuoted(i: Int, to: Int, q: Char): Int {
        var j = i + 1
        while (j < to) {
            val d = t[j]
            if (d == '\\') j += 2
            else if (d == q) return minOf(j + 1, to)
            else if (d == '\n') return j
            else j++
        }
        return to
    }

    private fun isCssProperty(from: Int, to: Int): Boolean {
        var k = from
        while (k < to) {
            val d = t[k]
            if (d == ';' || d == '}') return true
            if (d == '{') return false // "a:hover {" is a selector
            k++
        }
        return true
    }

    fun lexCss(from: Int, to: Int) {
        var i = from
        var depth = 0
        var inValue = false
        while (i < to) {
            if (stop()) return
            val ch = t[i]
            when {
                ch.isWhitespace() -> i++
                ch == '/' && i + 1 < to && t[i + 1] == '*' -> {
                    val close = t.indexOf("*/", i + 2)
                    val end = if (close < 0 || close + 2 > to) to else close + 2
                    add(i, end, K_COMMENT); i = end
                }
                ch == '"' || ch == '\'' -> { val end = cssQuoted(i, to, ch); add(i, end, K_STRING); i = end }
                ch == '{' -> { depth++; inValue = false; i++ }
                ch == '}' -> { if (depth > 0) depth--; inValue = false; i++ }
                ch == ';' -> { inValue = false; i++ }
                ch == '@' -> {
                    var j = i + 1
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '-')) j++
                    add(i, j, K_KEYWORD); i = j
                }
                ch == '!' -> {
                    if (t.startsWith("!important", i)) { add(i, i + 10, K_KEYWORD); i += 10 } else i++
                }
                ch == '#' -> {
                    var j = i + 1
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == '_')) j++
                    add(i, j, if (inValue) K_NUMBER else K_CLASS); i = j
                }
                ch == '.' && !inValue && i + 1 < to && (t[i + 1].isLetter() || t[i + 1] == '_' || t[i + 1] == '-') -> {
                    var j = i + 1
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == '_')) j++
                    add(i, j, K_CLASS); i = j
                }
                ch.isDigit() || (ch == '.' && i + 1 < to && t[i + 1].isDigit()) ||
                    ((ch == '-' || ch == '+') && inValue && i + 1 < to && t[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '.' || t[j] == '%')) j++
                    add(i, j, K_NUMBER); i = j
                }
                ch.isLetter() || ch == '_' || ch == '-' -> {
                    var j = i + 1
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == '_')) j++
                    val next = nextNonSpace(j, to)
                    val kind = when {
                        depth > 0 && !inValue && next == ':' && isCssProperty(j, to) -> { inValue = true; K_ATTR }
                        next == '(' -> K_FUNC
                        !inValue -> K_TAG
                        else -> 0
                    }
                    if (kind != 0) add(i, j, kind)
                    i = j
                }
                else -> i++
            }
        }
    }

    // HTML (with embedded <style> and <script>)
    private fun indexOfIgnoreCase(needle: String, from: Int, to: Int): Int {
        val idx = t.indexOf(needle, from, ignoreCase = true)
        return if (idx < 0 || idx >= to) -1 else idx
    }

    fun lexHtml(from: Int, to: Int) {
        var i = from
        while (i < to) {
            if (stop()) return
            val ch = t[i]
            if (ch == '<') {
                if (t.startsWith("<!--", i)) {
                    val close = t.indexOf("-->", i + 4)
                    val end = if (close < 0 || close + 3 > to) to else close + 3
                    add(i, end, K_COMMENT); i = end; continue
                }
                if (t.startsWith("<!", i) || t.startsWith("<?", i)) {
                    val close = t.indexOf('>', i)
                    val end = if (close < 0 || close + 1 > to) to else close + 1
                    add(i, end, K_KEYWORD); i = end; continue
                }
                if (i + 1 < to && (t[i + 1].isLetter() || t[i + 1] == '/')) { i = lexTag(i, to); continue }
            }
            if (ch == '&') {
                var j = i + 1
                while (j < to && j - i < 12 && (t[j].isLetterOrDigit() || t[j] == '#')) j++
                if (j < to && t[j] == ';' && j > i + 1) { add(i, j + 1, K_NUMBER); i = j + 1; continue }
            }
            i++
        }
    }

    private fun lexTag(start: Int, to: Int): Int {
        var i = start + 1
        val closing = t[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < to && (t[i].isLetterOrDigit() || t[i] == ':' || t[i] == '-' || t[i] == '_')) i++
        val name = t.substring(nameStart, i).lowercase()
        add(start, i, K_TAG)
        var selfClose = false
        loop@ while (i < to) {
            if (stop()) return to
            val ch = t[i]
            when {
                ch.isWhitespace() -> i++
                ch == '>' -> { add(i, i + 1, K_TAG); i++; break@loop }
                ch == '/' && i + 1 < to && t[i + 1] == '>' -> { add(i, i + 2, K_TAG); i += 2; selfClose = true; break@loop }
                ch == '<' -> break@loop // unterminated tag: let the caller carry on
                else -> {
                    val attrStart = i
                    while (i < to && !t[i].isWhitespace() && t[i] != '=' && t[i] != '>' &&
                        !(t[i] == '/' && i + 1 < to && t[i + 1] == '>')
                    ) i++
                    if (i == attrStart) { i++; continue@loop }
                    add(attrStart, i, K_ATTR)
                    var j = i
                    while (j < to && t[j].isWhitespace()) j++
                    if (j < to && t[j] == '=') {
                        j++
                        while (j < to && t[j].isWhitespace()) j++
                        if (j < to && (t[j] == '"' || t[j] == '\'')) {
                            val q = t[j]
                            var e = j + 1
                            while (e < to && t[e] != q) e++
                            e = minOf(e + 1, to)
                            add(j, e, K_STRING); i = e
                        } else {
                            var e = j
                            while (e < to && !t[e].isWhitespace() && t[e] != '>') e++
                            add(j, e, K_STRING); i = e
                        }
                    }
                }
            }
        }
        if (!closing && !selfClose && (name == "script" || name == "style")) {
            val close = indexOfIgnoreCase("</$name", i, to)
            val end = if (close < 0) to else close
            if (name == "script") lexCode(JS, i, end) else lexCss(i, end)
            return end
        }
        return i
    }
}

// --- analyzer ----------------------------------------------------------------------

private class HighlightAnalyzer(private val kind: HighlightKind) : SimpleAnalyzeManager<Any>() {

    override fun analyze(text: StringBuilder, delegate: SimpleAnalyzeManager<Any>.Delegate<Any>): Styles {
        val len = text.length
        val lexer = Lexer(text) { delegate.isCancelled }
        if (len <= MAX_CHARS) {
            when (kind) {
                HighlightKind.PYTHON -> lexer.lexCode(PY, 0, len)
                HighlightKind.JAVASCRIPT -> lexer.lexCode(JS, 0, len)
                HighlightKind.TYPESCRIPT -> lexer.lexCode(TS, 0, len)
                HighlightKind.LUA -> lexer.lexCode(LUA, 0, len)
                HighlightKind.HTML -> lexer.lexHtml(0, len)
                HighlightKind.CSS -> lexer.lexCss(0, len)
            }
        }
        var lineCount = 1
        for (ch in text) if (ch == '\n') lineCount++

        val builder = MappedSpans.Builder(maxOf(lineCount, 16))
        if (!lexer.stopped && lexer.n > 0) fill(text, lexer, builder, lineCount)
        builder.determine(lineCount - 1)
        val styles = Styles(builder.build())
        styles.finishBuilding()
        return styles
    }

    private fun span(b: MappedSpans.Builder, line: Int, col: Int, style: Long) {
        b.add(line, SpanFactory.obtainNoExt(col, style))
    }

    /** Turns the flat token list into per-line spans (tokens spanning lines are split). */
    private fun fill(text: StringBuilder, lx: Lexer, b: MappedSpans.Builder, lineCount: Int) {
        val n = text.length
        val starts = IntArray(lineCount)
        var li = 1
        for (i in 0 until n) {
            if (text[i] == '\n' && li < lineCount) { starts[li] = i + 1; li++ }
        }
        var ti = 0
        for (line in 0 until lineCount) {
            val ls = starts[line]
            var le = if (line + 1 < lineCount) starts[line + 1] - 1 else n
            if (le > ls && text[le - 1] == '\r') le--
            while (ti < lx.n && lx.te[ti] <= ls) ti++
            var cursor = ls
            var tj = ti
            while (tj < lx.n && lx.ts[tj] < le) {
                val s = maxOf(lx.ts[tj], ls, cursor)
                val e = minOf(lx.te[tj], le)
                if (e > s) {
                    if (s > cursor) span(b, line, cursor - ls, NORMAL)
                    span(b, line, s - ls, STYLES[lx.tk[tj].toInt()])
                    cursor = e
                }
                tj++
            }
            if (cursor < le) span(b, line, cursor - ls, NORMAL)
        }
    }
}
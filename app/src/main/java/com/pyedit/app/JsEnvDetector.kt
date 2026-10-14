package com.pyedit.app

/**
 * Decides whether JS/TS source needs a browser. Lightweight static analysis:
 * comments and string literals are removed first (template-literal
 * ${expressions} are kept as code), `typeof document` style guards are
 * ignored, and names the program declares itself are ignored.
 *
 * Limitations: regex literals are not understood, and dynamic access such as
 * globalThis["doc"+"ument"] can't be seen. Use the long-press-Run override
 * for those. setTimeout/setInterval/fetch count as browser APIs because the
 * script engine has no timers or network.
 */
object JsEnvDetector {

    data class Verdict(val browser: Boolean, val reason: String?)

    private val browserApi = Regex(
        "(?<![.\\w$])(document|window|navigator|localStorage|sessionStorage|alert|confirm|" +
            "requestAnimationFrame|XMLHttpRequest|fetch|setTimeout|setInterval|HTMLElement)(?![\\w$])"
    )
    private val viaGlobalThis = Regex("globalThis\\s*\\.\\s*(document|window|navigator)(?![\\w$])")
    private val typeofGuard = Regex("typeof\\s+[A-Za-z_$][\\w$]*")
    private val declared = Regex("\\b(?:const|let|var|function|class)\\s+([A-Za-z_$][\\w$]*)")

    fun detect(source: String): Verdict {
        val code = stripNonCode(source).replace(typeofGuard, " ")
        val own = declared.findAll(code).map { it.groupValues[1] }.toSet()

        viaGlobalThis.find(code)?.let { return Verdict(true, "uses globalThis.${it.groupValues[1]}") }
        for (m in browserApi.findAll(code)) {
            val name = m.groupValues[1]
            if (name in own) continue
            val why = if (name == "setTimeout" || name == "setInterval" || name == "fetch")
                "uses $name, which the script engine doesn't provide"
            else "uses the browser API '$name'"
            return Verdict(true, why)
        }
        return Verdict(false, null)
    }

    fun stripNonCode(src: String): String {
        val out = StringBuilder(src.length)
        val n = src.length
        var i = 0
        var braceDepth = 0
        var inTemplate = false
        val tplStack = ArrayList<Int>()

        while (i < n) {
            val c = src[i]
            if (inTemplate) {
                when {
                    c == '\\' -> i += 2
                    c == '`' -> { inTemplate = false; i++ }
                    c == '$' && i + 1 < n && src[i + 1] == '{' -> {
                        tplStack.add(braceDepth)
                        braceDepth++
                        inTemplate = false
                        out.append(' ')
                        i += 2
                    }
                    else -> i++
                }
                continue
            }
            when {
                c == '/' && i + 1 < n && src[i + 1] == '/' -> { while (i < n && src[i] != '\n') i++ }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' || c == '\'' -> {
                    i++
                    while (i < n && src[i] != c) { if (src[i] == '\\') i++; i++ }
                    i++
                    out.append(' ')
                }
                c == '`' -> { inTemplate = true; i++ }
                c == '{' -> { braceDepth++; out.append(c); i++ }
                c == '}' -> {
                    braceDepth--
                    if (tplStack.isNotEmpty() && tplStack.last() == braceDepth) {
                        tplStack.removeAt(tplStack.size - 1)
                        inTemplate = true
                    } else out.append(c)
                    i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
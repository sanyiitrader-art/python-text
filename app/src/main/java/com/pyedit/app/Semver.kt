package com.pyedit.app

/** Just enough npm semver: versions, ranges (^ ~ >= <= > < = x-ranges, hyphen, ||). */
object Semver {

    class V(val major: Long, val minor: Long, val patch: Long, val pre: List<String>) : Comparable<V> {
        override fun compareTo(other: V): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            if (pre.isEmpty() && other.pre.isEmpty()) return 0
            if (pre.isEmpty()) return 1
            if (other.pre.isEmpty()) return -1
            for (i in 0 until maxOf(pre.size, other.pre.size)) {
                val a = pre.getOrNull(i)
                val b = other.pre.getOrNull(i)
                if (a == null) return -1
                if (b == null) return 1
                val an = a.toLongOrNull()
                val bn = b.toLongOrNull()
                val c = when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
                if (c != 0) return c
            }
            return 0
        }
    }

    private val full = Regex("^[=vV]*\\s*(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?$")
    private val partialRe =
        Regex("^(\\d+|[xX*])(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?$")
    private val wild = setOf("x", "X", "*")

    fun parse(s: String): V? {
        val m = full.find(s.trim()) ?: return null
        return V(
            m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong(),
            m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        )
    }

    private class Partial(val major: Long?, val minor: Long?, val patch: Long?, val pre: List<String>)

    private fun partial(raw: String): Partial? {
        val s = raw.trim().trimStart('=', 'v', 'V').trim()
        if (s.isEmpty() || s in wild) return Partial(null, null, null, emptyList())
        val m = partialRe.find(s) ?: return null
        fun num(i: Int): Long? {
            val g = m.groupValues[i]
            return if (g.isEmpty() || g in wild) null else g.toLongOrNull()
        }
        return Partial(num(1), num(2), num(3), m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList())
    }

    private fun v(a: Long, b: Long, c: Long, pre: List<String> = emptyList()) = V(a, b, c, pre)

    private val any: (V) -> Boolean = { true }
    private val none: (V) -> Boolean = { false }

    private fun comparator(tok: String): ((V) -> Boolean)? {
        var op = ""
        var rest = tok
        for (o in listOf(">=", "<=", ">", "<", "^", "~", "=")) {
            if (tok.startsWith(o)) { op = o; rest = tok.substring(o.length); break }
        }
        val p = partial(rest) ?: return null
        val maj = p.major
        val min = p.minor
        val pat = p.patch
        val lo = if (maj != null) v(maj, min ?: 0, pat ?: 0, p.pre) else null
        fun range(low: V, high: V): (V) -> Boolean = { x -> x >= low && x < high }

        return when (op) {
            "", "=" -> when {
                maj == null -> any
                min == null -> range(v(maj, 0, 0), v(maj + 1, 0, 0))
                pat == null -> range(v(maj, min, 0), v(maj, min + 1, 0))
                else -> { x -> x.compareTo(lo!!) == 0 }
            }
            "^" -> {
                if (maj == null) return any
                val hi = when {
                    maj > 0 -> v(maj + 1, 0, 0)
                    min == null -> v(1, 0, 0)
                    min > 0 -> v(0, min + 1, 0)
                    pat == null -> v(0, 1, 0)
                    else -> v(0, 0, pat + 1)
                }
                range(lo!!, hi)
            }
            "~" -> {
                if (maj == null) return any
                range(lo!!, if (min == null) v(maj + 1, 0, 0) else v(maj, min + 1, 0))
            }
            ">=" -> if (maj == null) any else { x -> x >= lo!! }
            ">" -> when {
                maj == null -> none
                min == null -> { x -> x >= v(maj + 1, 0, 0) }
                pat == null -> { x -> x >= v(maj, min + 1, 0) }
                else -> { x -> x > lo!! }
            }
            "<" -> if (maj == null) none else { x -> x < lo!! }
            "<=" -> when {
                maj == null -> any
                min == null -> { x -> x < v(maj + 1, 0, 0) }
                pat == null -> { x -> x < v(maj, min + 1, 0) }
                else -> { x -> x <= lo!! }
            }
            else -> null
        }
    }

    private fun andSet(part: String): ((V) -> Boolean)? {
        val p = part.trim()
        if (p.isEmpty()) return any
        if (p.contains(" - ")) {
            val (a, b) = p.split(" - ", limit = 2)
            val low = comparator(">=" + a.trim()) ?: return null
            val high = comparator("<=" + b.trim()) ?: return null
            return { x -> low(x) && high(x) }
        }
        val preds = ArrayList<(V) -> Boolean>()
        for (tok in p.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            preds.add(comparator(tok) ?: return null)
        }
        return { x -> preds.all { it(x) } }
    }

    fun satisfies(version: String, range: String): Boolean {
        val v = parse(version) ?: return false
        val allowPre = Regex("\\d-[0-9A-Za-z]").containsMatchIn(range)
        if (v.pre.isNotEmpty() && !allowPre) return false
        for (part in range.split("||")) {
            val set = andSet(part) ?: continue
            if (set(v)) return true
        }
        return false
    }

    fun maxSatisfying(versions: Collection<String>, range: String): String? {
        var best: V? = null
        var bestStr: String? = null
        for (s in versions) {
            val v = parse(s) ?: continue
            if (!satisfies(s, range)) continue
            if (best == null || v > best) { best = v; bestStr = s }
        }
        return bestStr
    }

    /** git / URL / file / alias dependency specs are not supported. */
    fun isUnsupportedSpec(range: String): Boolean {
        val r = range.trim()
        return r.contains('/') || r.startsWith("git") || r.startsWith("http") || r.startsWith("file:") ||
            r.startsWith("npm:") || r.startsWith("link:") || r.startsWith("workspace:")
    }
}
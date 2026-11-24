package com.lawyye.wired

/*
 * Pure logic without Android dependencies, kept apart from the views so it can be unit-tested
 * on the JVM in CI (see app/src/test).
 */

/** Saved app keys ("package/activity", "#w" for the work profile) and how to repair them. */
object AppKeys {

    /** "pkg/cls#w" → "pkg#w", "pkg/cls" → "pkg". */
    fun packageOf(key: String): String {
        val work = key.endsWith(WORK)
        val base = if (work) key.dropLast(WORK.length) else key
        return base.substringBefore('/') + if (work) WORK else ""
    }

    /**
     * Saved keys whose activity no longer exists, mapped to the app's current launcher activity.
     * Apps sometimes rename that activity in an update (an activity-alias for a new icon), which
     * used to drop them from the dock, un-hide them and reset their launch count. Only a package
     * that now has exactly one launcher activity is repaired, so a guess never picks the wrong one.
     */
    fun heal(saved: Collection<String>, current: Collection<String>): Map<String, String> {
        if (saved.isEmpty() || current.isEmpty()) return emptyMap()
        val present = current.toHashSet()
        val byPackage = current.groupBy { packageOf(it) }
        val out = HashMap<String, String>()
        for (k in saved) {
            if (k in present) continue
            val candidates = byPackage[packageOf(k)] ?: continue
            if (candidates.size == 1) out[k] = candidates[0]
        }
        return out
    }

    private const val WORK = "#w"
}

/** App search: ranking, and typing in the wrong keyboard layout ("еудупкфь" finds Telegram). */
object Search {

    /** 0 = label starts with the query, 1 = a word starts with it, 2 = inside the label, 3 = package. */
    fun rank(label: String, pkg: String, q: String): Int {
        if (q.isEmpty()) return -1
        val idx = label.indexOf(q)
        return when {
            idx == 0 -> 0
            idx > 0 && !label[idx - 1].isLetterOrDigit() -> 1
            idx > 0 -> 2
            pkg.contains(q) -> 3
            else -> -1
        }
    }

    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

    /** The same keys pressed in the other layout: "ntktuhfv" → "телеграм", "еудупкфь" → "telegram". */
    fun swapLayout(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.lowercaseChar()
            val i = EN.indexOf(c)
            val j = RU.indexOf(c)
            out.append(
                when {
                    i >= 0 -> RU[i]
                    j >= 0 -> EN[j]
                    else -> c
                },
            )
        }
        return out.toString()
    }
}

/** Launch counts are stored as "key<TAB>count" lines. */
object Counts {
    fun parse(raw: String?): Map<String, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Int>()
        for (line in raw.split('\n')) {
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) continue
            val n = line.substring(tab + 1).toIntOrNull() ?: continue
            out[line.substring(0, tab)] = n
        }
        return out
    }

    /** Keeps the map small: when it grows past 160 apps the least used are forgotten. */
    fun serialize(m: Map<String, Int>): String {
        val kept = if (m.size > 160) m.entries.sortedByDescending { it.value }.take(120).associate { it.key to it.value } else m
        return kept.entries.joinToString("\n") { it.key + "\t" + it.value }
    }
}

object Images {
    /**
     * The power-of-two step BitmapFactory decodes at, so the long edge stays at least [maxEdge]
     * (zooming in never shows a blurry picture) without decoding needless pixels.
     */
    fun sampleFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        val longEdge = maxOf(width, height)
        var s = 1
        while (longEdge / (s * 2) >= maxEdge) s *= 2
        return s
    }
}

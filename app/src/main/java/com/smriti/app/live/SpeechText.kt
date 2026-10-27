package com.smriti.app.live

/**
 * What Smriti shows and what it says aloud for one answer. An answer can be several lines (a list of objects grouped by place,
 * a time and a place on separate lines). The screen shows all of it; the voice reads whole sentences, never a lone heading such as
 * "On the left side:". Plain Kotlin, unit-tested.
 */
object SpeechText {
    private const val SHOWN_MAX = 1500
    private const val SHORT_MAX = 340
    private const val DETAILED_MAX = 1000

    private val BULLET = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")
    private val MARKUP = Regex("""[*_`#]+""")

    /** the lines of an answer, tidied: no markdown marks, no bullets, no empty lines */
    private fun lines(answer: String): List<String> =
        answer.replace("\r", "").lines().map { MARKUP.replace(BULLET.replace(it, ""), "").replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }

    /** the whole answer for the screen, cut at a sentence end only when it is extremely long */
    fun shown(answer: String): String {
        val text = answer.replace("\r", "").lines().map { tidyForScreen(it) }.filter { it.isNotBlank() }.joinToString("\n").trim()
        if (text.length <= SHOWN_MAX) return text
        return cutAtBoundary(text, SHOWN_MAX) + "…"
    }

    /**
     * Short style: the first complete thought. A first line that is only a heading (ends with a colon, or is very short while more
     * follows) is joined with the lines after it, until a complete line has been collected.
     * Detailed style: everything, up to [DETAILED_MAX] characters.
     */
    fun spoken(answer: String, detailed: Boolean): String {
        val ls = lines(answer)
        if (ls.isEmpty()) return ""
        if (detailed) return cutAtBoundary(join(ls), DETAILED_MAX)
        // a list ("what objects do you see"): the user asked for all of it, so read as much as fits
        val isList = answer.lines().count { BULLET.containsMatchIn(it) || it.trim().endsWith(":") } >= 2
        if (isList) return cutAtBoundary(join(ls), SHORT_MAX)
        val out = ArrayList<String>()
        var len = 0
        for ((i, l) in ls.withIndex()) {
            out += l; len += l.length + 1
            val sentence = l.endsWith(".") || l.endsWith("!") || l.endsWith("?")
            val heading = l.endsWith(":") || (l.length < 25 && i < ls.lastIndex && !sentence)
            if (heading) { if (len >= SHORT_MAX) break; continue }
            // a complete line is enough, unless it is so short that the next line is part of the same thought
            if (len >= 40 || i == ls.lastIndex) break
        }
        return cutAtBoundary(join(out), SHORT_MAX)
    }

    private fun join(ls: List<String>): String =
        ls.joinToString(" ") { if (it.endsWith(".") || it.endsWith("!") || it.endsWith("?") || it.endsWith(":") || it.endsWith(",")) it else "$it." }
            .replace(":.", ":")

    /** a line for the screen: bold marks removed, "*" and "-" bullets shown as a bullet dot, spaces trimmed */
    private fun tidyForScreen(line: String): String {
        val bullet = BULLET.containsMatchIn(line)
        val clean = MARKUP.replace(BULLET.replace(line, ""), "").replace(Regex("[ \t]+"), " ").trim()
        return if (bullet && clean.isNotEmpty()) "• $clean" else clean
    }

    /** the longest start of [text] that ends at a sentence or comma, within [max] characters */
    private fun cutAtBoundary(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.substring(0, max)
        val cut = maxOf(head.lastIndexOf(". "), head.lastIndexOf("! "), head.lastIndexOf("? "), head.lastIndexOf("\n"), head.lastIndexOf(", "))
        return if (cut > max / 2) head.substring(0, cut + 1).trim() else head.trim()
    }

    /** how long the caption stays after the voice ends: about 60 ms per character, between 8 and 40 seconds */
    fun captionHoldMs(shown: String): Long = (2_500L + 60L * shown.length).coerceIn(8_000L, 40_000L)
}

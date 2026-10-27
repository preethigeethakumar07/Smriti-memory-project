package com.smriti.app.query

import java.util.Calendar

sealed interface Intent {
    /** "what do you see": answer from the live camera. */
    data object Live : Intent

    /** "forget the last 10 minutes". */
    data class Forget(val minutes: Int) : Intent

    /** "where are my keys". */
    data class Where(val item: String) : Intent

    /** "what happened in the last 20 minutes" / "what was I doing at 4 pm". */
    data class Range(val fromMs: Long, val toMs: Long, val label: String) : Intent

    /** "what was the most important moment". */
    data object Highlight : Intent

    /** "what is in the kitchen". */
    data class InPlace(val place: String) : Intent

    /** "hello", "what can you do". */
    data object Help : Intent

    data class Search(val text: String) : Intent
}

/**
 * Rule-based router for SPOKEN questions. Speech comes back as free sentences ("hey Smriti, can you tell me where my
 * mouse is"), so the text is first cleaned (wake words, filler, punctuation), then matched on meaning, not on one exact
 * phrase. Instant, deterministic, no model needed for the common questions.
 */
object IntentParser {

    private val fillerPrefix = Regex("^(?:(?:hey|hi|ok|okay|hello)\\s+)?(?:smriti|smrithi|smirti)?\\s*[,.]?\\s*(?:(?:please|can you|could you|would you|will you|i want to know|i wanna know|i would like to know|do you know|tell me|let me know)\\s+)*")

    private val liveRe = Regex(
        "(what (do|can|did|are) (you|u) (see|seeing|saw|looking at|look at)|what you see|what am i (looking at|holding)|" +
            "what'?s (in front|around|here|there|this|that)|what is (in front|around|here|there|this|that)|describe|look at|" +
            "(^|\\s)about (this|the) (room|place|scene|view|area)|(tell|talk) (me )?about (this|the) (room|place|scene|view|area)|what (does it|do you) look like|can you see|what is going on|what'?s going on|what'?s happening now)"
    )
    private val forgetRe = Regex("forget.*?(?:last|past)\\s+(?:(\\d+|a|an|one)\\s*)?(minute|minutes|min|mins|hour|hours|hr|hrs)")
    private val lastRe = Regex("(?:last|past)\\s+(?:(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|half an?)\\s*)?(minute|minutes|min|mins|hour|hours|hr|hrs)")
    private val lastTimeRe = Regex("(?:last time|when (?:was|did) (?:i|you) (?:last )?)\\s*(?:that )?(?:you |i )?(?:saw|seen|see|had|used|use|noticed|spotted|kept|left)\\s+(?:a |an |the |my |our )?([a-z][a-z ]{1,30})")
    private val atRe =Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?")
    private val happenedRe = Regex("(what (happened|did i|was i|have i|have you)|summar|recap|timeline|how many)")
    private val highlightRe = Regex("(most important|biggest|best|key|main|top|interesting)\\s+(moment|scene|event|thing)|highlights?\\b|what stood out")
    private val placeRe = Regex("(?:what(?:'s| is| was| did you see| have you seen)|show me|list)\\s+(?:in|on|at)\\s+(?:the |my )?([a-z]+(?: room| area)?)")
    private val helpRe = Regex("^(hello|hi|hey|good (morning|afternoon|evening)|thanks|thank you|who are you|what can you do|help|how are you)\\b")

    // "where is X", "where did I put X", "find X", "I lost X", "did you see X", "show me X", "last seen X" ...
    private val whereWord = Regex("\\bwhere(?:'s|s)?\\b|whereabouts")
    private val findVerbs = Regex("\\b(find|locate|show me|looking for|look for|search for|lost|misplaced|can'?t find|cannot find|last seen|have you seen|did you see|do you see my|when did (?:i|you) (?:last )?(?:see|use|have)|seen my)\\b")
    private val itemCut = Regex(
        "^.*?\\b(?:where(?:'s|s)?(?: is| are| was| were| did (?:i|you)| do (?:i|you)| have (?:i|you)| has| can i| should i)?|" +
            "find|locate|show me|looking for|look for|search for|lost|misplaced|can'?t find|cannot find|last seen|have you seen|did you see|do you see|" +
            "when did (?:i|you) (?:last )?(?:see|use|have)|seen)\\b"
    )
    private val itemVerbs = Regex("^(?:(?:i|you|we)\\s+)?(?:put|kept|keep|left|leave|placed|place|stored|store|see|saw|seen|find|found|have|had|use|used)\\b")
    private val itemTrailer = Regex("\\b(?:is|are|was|were|now|right now|today|located|at the moment|please|anywhere|then)\\s*$")
    private val itemNoise = setOf("my", "the", "a", "an", "our", "your", "that", "this", "those", "these", "me", "i", "you", "it", "is", "are", "was", "were", "did", "do", "does", "to", "of", "in", "on", "at", "for", "any", "some")

    fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): Intent {
        var q = raw.lowercase().trim().trimEnd('?', '.', '!', ',').replace(Regex("\\s+"), " ")
        q = q.replace(Regex("[\"\u201C\u201D\u2018\u2019]"), "")
        q = fillerPrefix.replace(q, "").trim().ifEmpty { raw.lowercase().trim() }

        forgetRe.find(q)?.let { return Intent.Forget(toMinutes(it.groupValues[1], it.groupValues[2])) }
        if (helpRe.containsMatchIn(q) && q.split(" ").size <= 5) return Intent.Help

        // "show me the last time you saw a bottle", "when was the last time you used my laptop"
        lastTimeRe.find(q)?.let { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { return Intent.Where(it) } }

        // a "where is X" style question must beat the generic "what do you see"
        val asksWhere = whereWord.containsMatchIn(q) || findVerbs.containsMatchIn(q)
        if (!asksWhere && liveRe.containsMatchIn(q)) return Intent.Live

        if (highlightRe.containsMatchIn(q)) return Intent.Highlight

        if (happenedRe.containsMatchIn(q) || lastRe.containsMatchIn(q) || q.startsWith("what") && atRe.containsMatchIn(q)) {
            lastRe.find(q)?.let {
                val mins = toMinutes(it.groupValues[1], it.groupValues[2])
                return Intent.Range(nowMs - mins * 60_000L, nowMs, "the last $mins minutes")
            }
            atRe.find(q)?.let { m ->
                val centre = resolveClock(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3], nowMs)
                return Intent.Range(centre - 30 * 60_000L, centre + 30 * 60_000L, "around ${m.value.removePrefix("at ").trim()}")
            }
            if (q.contains("today") || q.contains("how many") || q.contains("summar") || q.contains("recap")) {
                return Intent.Range(startOfDay(nowMs), nowMs, "today")
            }
        }

        if (!whereWord.containsMatchIn(q)) placeRe.find(q)?.let { return Intent.InPlace(it.groupValues[1].trim()) }

        if (asksWhere) {
            extractItem(q)?.let { return Intent.Where(it) }
        }
        if (liveRe.containsMatchIn(q)) return Intent.Live
        return Intent.Search(q)
    }

    /** "can you tell me where my keyboard is" -> "keyboard". Returns null when nothing is left to look for. */
    fun extractItem(q: String): String? {
        var rest = itemCut.replace(q, "").trim()
        rest = itemVerbs.replace(rest, "").trim()
        rest = itemTrailer.replace(rest, "").trim()
        val words = rest.split(" ").filter { it.isNotBlank() && it !in itemNoise }
            .filter { it !in setOf("put", "kept", "keep", "left", "leave", "placed", "place", "see", "saw", "seen", "find", "found") }
        if (words.isEmpty()) return null
        return words.take(3).joinToString(" ")
    }

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty five" to 45, "half an" to 30, "half a" to 30
    )

    private fun toMinutes(num: String, unit: String): Int {
        val n = if (num.isEmpty()) 1 else num.toIntOrNull() ?: numberWords[num] ?: 1
        return if (unit.startsWith("h")) (if (num.startsWith("half")) 30 else n * 60) else n
    }

    /** Resolve "4" / "4 pm" to the most recent such time that is not in the future. */
    private fun resolveClock(hour: Int, minute: Int, meridiem: String, nowMs: Long): Long {
        val cleaned = meridiem.replace(".", "")
        val candidates = when {
            cleaned == "pm" -> listOf((hour % 12) + 12)
            cleaned == "am" -> listOf(hour % 12)
            hour > 12 -> listOf(hour)
            else -> listOf(hour % 12, (hour % 12) + 12)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        // a time that is still ahead today (it is 12:15 AM and the user says "11:30 PM") means yesterday
        val times = candidates.map { h ->
            val t = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (t > nowMs) t - 24 * 3_600_000L else t
        }
        return times.maxOrNull()!!
    }

    private fun startOfDay(nowMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

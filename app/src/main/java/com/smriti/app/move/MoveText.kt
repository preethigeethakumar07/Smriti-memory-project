package com.smriti.app.move

import kotlin.math.max

/** What a spoken request to the walk guide means. */
sealed class MoveIntent {
    /** "take me home", "I am lost" */
    object GoHome : MoveIntent()
    /** "I am going out", "start my walk": record the way so it can be walked back */
    object StartTrip : MoveIntent()
    /** "stop guiding", "I am home" */
    object Stop : MoveIntent()
    /** "take me to the chemist": the place name is looked up among the saved places */
    data class ToPlace(val name: String) : MoveIntent()
}

/** Turns what the user said into a [MoveIntent], or null when it is not about walking. Plain Kotlin, unit-tested. */
object MoveIntentParser {
    private val HOME = Regex(
        """\b(take|guide|lead|walk|bring|get|send) (me|us)( back)?( to)? (my )?home\b|\bhelp (me|us) (to |get |go |find my way )?(back )?(to )?(my )?home\b|""" +
            """\b(i want|i need|i would like) to (go|get|walk) (back )?home\b|\b(which way|what way|where) is (my )?home\b|\bhow (do|can) i (get|go) (back )?home\b|""" +
            """\b(i am|i'm|im) lost\b|\bi (don't|do not|cannot|can't) (know|find|remember) (the |my )?(way|where)\b""",
        RegexOption.IGNORE_CASE
    )
    // speech recognisers slip ("stay at me home"): in a short sentence "me home" is enough, but not "show me home screen"
    private val LOOSE_HOME = Regex("""\b(me|us) home\b(?!\s*(screen|page|address|videos?|photos?|pictures?))""", RegexOption.IGNORE_CASE)
    private val TRIP = Regex("""\b(i am|i'm|im) (going|heading) out\b|\bstart (my |a |the )?walk\b|\bgoing for a walk\b|\brecord (my |the )?(walk|way)\b|\bi (am|'m) leaving( the house)?\b""", RegexOption.IGNORE_CASE)
    private val STOP = Regex("""\bstop (the )?(guiding|guide|walk|walking guide|walk guide)\b|\b(i am|i'm|im) (at )?home( now)?\b|\bi (have )?reached( home)?\b|\bi('m| am) here\b""", RegexOption.IGNORE_CASE)
    private val PLACE = Regex("""\b(take|guide|lead|walk|bring) (me|us) to (the |my |a )?(.+?)\s*[?.!]*\s*$""", RegexOption.IGNORE_CASE)

    // Hindi, in Devanagari and as typed in English letters ("ghar le chalo"); no \b here, it does not work with Devanagari
    private val HI_HOME = Regex("""घर\s*(तक\s*)?(ले\s*चल|पहुँ?चा|पहुंचा|जाना\s*है|जाने\s*का\s*रास्ता|का\s*रास्ता)|मैं\s*खो\s*ग(य|ई)|ghar\s+(tak\s+)?(le\s+chal|pahuncha|jana\s+hai|ka\s+rasta)|mujhe\s+ghar|main\s+kho\s+gay""", RegexOption.IGNORE_CASE)
    private val HI_TRIP = Regex("""बाहर\s*जा\s*र|सैर\s*पर\s*जा|bahar\s+ja\s+rah|walk\s+par\s+ja""", RegexOption.IGNORE_CASE)
    private val HI_STOP = Regex("""(गाइड|रास्ता\s*बताना)\s*बंद|guide\s+band|guide\s+bandh""", RegexOption.IGNORE_CASE)

    fun parse(said: String): MoveIntent? {
        val s = said.trim().replace('’', '\'').replace('‘', '\'')       // speech recognisers often give curly apostrophes
        val home = HOME.containsMatchIn(s) || HI_HOME.containsMatchIn(s) || (LOOSE_HOME.containsMatchIn(s) && s.split(Regex("\\s+")).size <= 6)
        if ((STOP.containsMatchIn(s) || HI_STOP.containsMatchIn(s)) && !home) return MoveIntent.Stop
        if (home) return MoveIntent.GoHome
        if (TRIP.containsMatchIn(s) || HI_TRIP.containsMatchIn(s)) return MoveIntent.StartTrip
        PLACE.find(s)?.let { m -> m.groupValues[4].trim().takeIf { it.isNotEmpty() && it.split(" ").size <= 4 }?.let { return MoveIntent.ToPlace(it.lowercase()) } }
        return null
    }
}

/**
 * Telling places apart from the Wi-Fi networks heard there: a place is saved with its strongest networks, and later two lists of
 * networks are compared. This works when GPS is weak (inside a building, between tall buildings). Plain Kotlin, unit-tested.
 */
object WifiPrint {
    /** a network's address, hashed so the real address is never stored: 5 bytes of SHA-256 as 10 hex characters */
    fun hash(bssid: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(("smriti-wifi:" + bssid.trim().lowercase()).toByteArray())
        return d.take(5).joinToString("") { "%02x".format(it) }
    }

    fun encode(ids: List<String>): String = ids.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(",")

    fun decode(print: String): Set<String> = print.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** how alike two lists of networks are, 0 (nothing in common) to 1 (the same networks) */
    fun similarity(saved: String, now: String): Double {
        val a = decode(saved); val b = decode(now)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.intersect(b).size.toDouble()
        return common / max(a.size, b.size)
    }

    /** true when enough of the saved networks are heard again (at least half, and at least two) */
    fun isHere(saved: String, now: String): Boolean {
        val a = decode(saved)
        if (a.size < 2) return false
        return a.intersect(decode(now)).size >= max(2, (a.size + 1) / 2)
    }
}

/** A recorded walk as text and back: "x,y;x,y;..." in metres east / north of the start. */
object RouteText {
    fun encode(points: List<Pt>): String = points.joinToString(";") { "%.1f,%.1f".format(java.util.Locale.US, it.x, it.y) }

    fun decode(text: String): List<Pt> = text.split(";").mapNotNull { s ->
        val p = s.split(",")
        if (p.size != 2) null else {
            val x = p[0].toDoubleOrNull(); val y = p[1].toDoubleOrNull()
            if (x == null || y == null) null else Pt(x, y)
        }
    }

    /** the points of a walk made around [from], shown around [to] (both are map positions of the walk's start) */
    fun reframe(points: List<Pt>, from: LatLon, to: LatLon): List<Pt> =
        points.map { Geo.toLocal(to, Geo.fromLocal(from, it.x, it.y)) }
}

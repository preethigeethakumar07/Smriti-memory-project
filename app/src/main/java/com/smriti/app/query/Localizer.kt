package com.smriti.app.query

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Replies in the user's language. Hindi is written out in full. For the other Indian languages Smriti understands
 * the question (see [MultiLang]) but still answers in English, and the voice switches to English for that answer so
 * it is not read out in the wrong accent. [replyTag] decides which.
 */
object Localizer {

    /** The language the answer will be WRITTEN and SPOKEN in. */
    fun replyTag(chosen: String, ttsHasVoice: Boolean): String =
        if (chosen.startsWith("hi", ignoreCase = true) && ttsHasVoice) "hi-IN" else "en-IN"

    fun isHindi(tag: String) = tag.startsWith("hi", ignoreCase = true)

    private fun hi(en: String) = MultiLang.nativeName(en, "hi")

    // ---- small phrases ------------------------------------------------------------------------------------------------

    fun help() = "नमस्ते, मैं स्मृति हूँ। मैं कैमरे से जो देखती हूँ उसे याद रखती हूँ। पूछिए: आपको क्या दिख रहा है, मेरी चाबी कहाँ है, या पिछले 10 मिनट में क्या हुआ।"

    fun notSeen(item: String) = "मैंने ${hi(item)} अभी तक नहीं देखा। मुझे बस वही पता है जो कैमरे ने दिखाया।"

    fun cantAnswer(text: String) = "मैंने सुना \"${text.take(80)}\", लेकिन मैं इसका जवाब नहीं दे सकती। पूछिए: आपको क्या दिख रहा है, मेरी चाबी कहाँ है, या पिछले 10 मिनट में क्या हुआ।"

    fun forgot(n: Int, minutes: Int) = if (n == 0) "पिछले $minutes मिनट में भूलने जैसा कुछ नहीं था।" else "ठीक है, पिछले $minutes मिनट की $n यादें मिटा दीं।"

    fun cameraNotReady() = "कैमरा अभी तैयार नहीं है।"

    fun looking(objects: List<String>): String {
        val names = objects.take(5).map { hi(it) }
        return if (names.isEmpty()) "मुझे कुछ खास नहीं दिख रहा।" else "मुझे ${names.joinToString(", ")} दिख रहा है।"
    }

    fun inPlace(room: String, items: List<Pair<String, Long>>, now: Long) =
        "${roomHi(room) ?: room} में मैंने देखा: " + items.joinToString(", ") { "${hi(it.first)} (${ago(now - it.second)})" } + "।"

    // ---- time -----------------------------------------------------------------------------------------------------------

    fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 45 -> "अभी-अभी"
            s < 90 -> "1 मिनट पहले"
            s < 3600 -> "${s / 60} मिनट पहले"
            s < 7200 -> "लगभग 1 घंटा पहले"
            s < 86_400 -> "${s / 3600} घंटे पहले"
            else -> "${s / 86_400} दिन पहले"
        }
    }

    private fun partOfDay(hour: Int) = when { hour < 4 -> "रात"; hour < 12 -> "सुबह"; hour < 16 -> "दोपहर"; hour < 20 -> "शाम"; else -> "रात" }

    fun clock(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val h12 = (c.get(Calendar.HOUR) .let { if (it == 0) 12 else it })
        return "${partOfDay(c.get(Calendar.HOUR_OF_DAY))} $h12:${"%02d".format(c.get(Calendar.MINUTE))} बजे"
    }

    fun whenText(at: Long, now: Long): String {
        val dayDiff = (startOfDay(now) - startOfDay(at)) / 86_400_000L
        return when {
            now - at < 3_600_000L -> "${ago(now - at)} (${clock(at)})"
            dayDiff == 0L -> "आज ${clock(at)} (${ago(now - at)})"
            dayDiff == 1L -> "कल ${clock(at)}"
            else -> "${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))} को ${clock(at)}"
        }
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- place ----------------------------------------------------------------------------------------------------------

    private val rooms = mapOf(
        "kitchen" to "रसोई", "bedroom" to "शयनकक्ष", "bathroom" to "बाथरूम", "living room" to "बैठक", "dining room" to "भोजन कक्ष",
        "dining area" to "भोजन क्षेत्र", "office" to "दफ़्तर", "workspace" to "कार्य-स्थान", "garage" to "गैराज", "garden" to "बगीचा",
        "classroom" to "कक्षा", "restaurant" to "रेस्टोरेंट", "street" to "सड़क", "car" to "कार"
    )

    fun roomHi(zone: String): String? = rooms[zone]

    /** "on the table, next to the cup" -> "मेज़ पर, कप के पास" (parts that cannot be translated are dropped). */
    private fun relation(how: String): String? {
        if (how.isBlank() || how.startsWith("I see") || how.startsWith("Nothing")) return null
        val parts = how.split(", ").mapNotNull { p ->
            Regex("^on the (.+)$").find(p)?.let { "${hi(it.groupValues[1])} पर" }
                ?: Regex("^next to the (.+)$").find(p)?.let { "${hi(it.groupValues[1])} के पास" }
                ?: Regex("^with the (.+) in view$").find(p)?.let { "${hi(it.groupValues[1])} भी दिख रहा था" }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** ", मेज़ पर, रसोई में" */
    fun placeText(zone: String, how: String): String {
        val rel = relation(how)
        val room = if (zone == "somewhere" || zone.isBlank()) null else (rooms[zone]?.let { "$it में" } ?: "$zone के पास")
        return listOfNotNull(rel, room).joinToString(", ", prefix = if (rel != null || room != null) ", " else "")
    }

    // ---- scene block ------------------------------------------------------------------------------------------------------

    fun level(x: String) = when (x) { "Low" -> "कम"; "Medium" -> "मध्यम"; else -> "अधिक" }

    fun sceneBlock(title: String, from: Long, to: Long, spanMin: Long, importance: Float, motion: String, interaction: String, objects: List<String>): String =
        "दृश्य: $title\n" +
            "समय: ${clock(from)} से ${clock(to)} (${if (spanMin <= 0) "एक मिनट से कम" else "$spanMin मिनट"})\n" +
            "महत्व: ${"%.2f".format(importance)}   गति: ${level(motion)}   आपसी संपर्क: ${level(interaction)}\n" +
            "पहचानी गई वस्तुएँ: ${objects.take(6).joinToString(", ") { hi(it) }}"
}

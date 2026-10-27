package com.smriti.app.ai

/**
 * Every remembered moment gets a written list of the objects Gemma-4 can see in it, kept with the moment (in its caption, after
 * "Objects:"). The picture vector finds scenes; this list makes sure a small or half-hidden thing is still found by name, and gives
 * the answer step the full inventory of the moment to read. The prompts and the parsing live here, free of Android, so they are tested.
 */
object Inventory {
    const val MARK = "Objects:"

    const val SYSTEM =
        "You are an exhaustive visual inventory tool. Look at the whole picture systematically: foreground, background, left, right, top, bottom, " +
            "corners, shelves, tables, floors, walls, windows, hands, screens and anything held or worn. List EVERY distinct object you can see. " +
            "Do not skip small, partly hidden, blurry, dark or far away objects. Include furniture, devices, screens, containers, bottles, bags, clothes, " +
            "food, tools, papers, books, plants, vehicles, animals, signs and decorations. Name each object plainly (for example \"steel bottle\", \"laptop\", " +
            "\"white mug\", \"key bunch\", \"pill strip\"). If there are several of the same thing, write the count (\"3 books\"). " +
            "Add any text, brand name or number you can read as \"text: ...\". Never name or describe people, only say \"person\" and what they hold or wear. " +
            "Reply with one comma separated list only, no sentences."

    const val QUESTION = "List every object in this picture."

    /** the same inventory but for a person asking right now: grouped by where it is, and complete */
    const val LIVE_LIST_SYSTEM =
        "You are Smriti, looking through a phone camera right now. The user wants to know what is in view. Scan the whole picture systematically " +
            "(foreground, background, left, right, top, bottom, surfaces, hands, screens) and name EVERY object you can see, including small, partly hidden or distant ones. " +
            "Group them by where they are (for example \"On the table: ...\", \"Behind: ...\"). Write counts for repeated items. Read any visible text exactly. " +
            "Never name people, only say \"a person\". Do not stop early and do not skip anything; if you are unsure of an object, say \"something that looks like ...\"."

    /** a question about one thing in the live view: look everywhere, answer directly, and still mention matching items */
    const val LIVE_ASK_SYSTEM =
        "You are Smriti, a calm assistant looking through a phone camera right now. Before you answer, scan the WHOLE picture systematically " +
            "(foreground, background, left, right, top, bottom, surfaces, hands, screens), including small, partly hidden or distant objects. " +
            "Then answer exactly what the user asked, directly and completely. If they ask about an object, say whether it is there, where in the picture, " +
            "what it looks like, and how many there are; if several objects fit, mention each one. If they ask for everything or a list, name every object. " +
            "Count carefully when asked how many. Read text exactly when asked to read. Say only what you can see and say so plainly if it is not visible. " +
            "Never guess names of people. If the question is not about the picture at all (general knowledge, calculation, advice), ignore the picture and just answer it. " +
            "Keep the answer short and plain unless a list was asked for."

    private val LIST_ASK = Regex("""\b(what|which)\b.*\b(objects?|things?|items?|stuff)\b|\blist\b|\beverything\b|\ball (the )?(objects?|things?|items?)\b|\bwhat('?s| is| do you see| can you see| all)\b.*\b(in|on|around|here|view|front|frame|picture|see)\b|\bidentify\b|\bwhat do you see\b""", RegexOption.IGNORE_CASE)

    /** true when the question asks for the whole scene rather than one thing */
    fun wantsList(question: String) = LIST_ASK.containsMatchIn(question)

    private val TOO_LONG = 5
    private val JUNK = setOf("none", "n/a", "nothing", "no objects", "objects", "the picture", "image")

    /** turns the model's reply into clean, lower-case, unique names */
    fun parse(raw: String?, max: Int = 40): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (piece in raw.split(',', '\n', ';')) {
            val t = piece.trim().trim('-', '*', '.', '•', ' ', '"').replace(Regex("""^\d+[.)]\s*"""), "").trim()
            if (t.isEmpty() || t.lowercase() in JUNK) continue
            // a sentence is not an object name; a "text: ..." reading may be longer
            if (!t.startsWith("text:", ignoreCase = true) && t.split(' ').size > TOO_LONG) continue
            seen += t.lowercase()
            if (seen.size >= max) break
        }
        return seen.toList()
    }

    /** the caption to store: what the detector said, then the inventory, within [limit] characters */
    fun merge(caption: String, objects: List<String>, limit: Int = 700): String {
        val base = stripObjects(caption).takeUnless { it.startsWith("Ready.") } ?: ""      // the idle placeholder says nothing about the picture
        if (objects.isEmpty()) return base.take(limit)
        val list = "$MARK ${objects.joinToString(", ")}"
        val room = (limit - list.length - 1).coerceAtLeast(0)
        return if (room == 0) list.take(limit) else (base.take(room) + " " + list).trim()
    }

    fun stripObjects(caption: String): String {
        val i = caption.indexOf(MARK)
        return if (i < 0) caption.trim() else caption.substring(0, i).trim()
    }

    fun objectsOf(caption: String): List<String> {
        val i = caption.indexOf(MARK)
        return if (i < 0) emptyList() else caption.substring(i + MARK.length).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val STOP = setOf(
        "a", "an", "the", "my", "your", "our", "his", "her", "their", "its", "is", "are", "was", "were", "do", "does", "did", "where", "when", "what",
        "which", "who", "how", "have", "has", "had", "i", "me", "you", "we", "it", "this", "that", "of", "in", "on", "at", "to", "from", "for", "with",
        "and", "or", "see", "saw", "seen", "show", "find", "look", "looking", "put", "keep", "kept", "left", "any", "some", "there", "here", "last", "time",
        "recording", "video", "picture", "frame", "camera", "please", "tell", "can", "could", "about", "again", "today", "yesterday"
    )

    /** the nouns worth matching in a question: "where is my steel bottle" gives [steel, bottle] */
    fun keywords(question: String): List<String> =
        question.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.length > 1 && it !in STOP }.map(::singular).distinct()

    private fun singular(w: String) = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 3 && w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /**
     * True when the stored inventory of a moment names what the question is about. Every keyword must appear (as a whole word, either
     * singular or plural) so "steel bottle" does not match a moment that only lists "water bottle".
     */
    fun matches(question: String, caption: String): Boolean {
        val words = keywords(question)
        if (words.isEmpty()) return false
        val have = objectsOf(caption).joinToString(" ").lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() }.map(::singular).toSet()
        return words.all { it in have }
    }
}

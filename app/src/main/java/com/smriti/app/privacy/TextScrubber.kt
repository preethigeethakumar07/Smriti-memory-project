package com.smriti.app.privacy

/**
 * Removes personal identifiers from any text before it is written to memory
 * (OCR output, captions, spoken queries). Pixels are not kept, and neither are
 * phone numbers, card or ID numbers, e-mails or number plates.
 */
object TextScrubber {

    private val rules = listOf(
        // e-mail
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[email]",
        // Indian vehicle plate, e.g. KA01AB1234 or KA 01 AB 1234
        Regex("\\b[A-Z]{2}[ -]?\\d{1,2}[ -]?[A-Z]{1,3}[ -]?\\d{3,4}\\b") to "[plate]",
        // Aadhaar-style 12 digits (with optional spaces), card numbers (13-19 digits)
        Regex("\\b(?:\\d[ -]?){12,19}\\b") to "[id-number]",
        // phone numbers and other long digit runs
        Regex("\\+?\\d[\\d -]{7,}\\d") to "[number]"
    )

    fun scrub(text: String): String {
        var out = text.replace('\n', ' ').trim()
        for ((re, token) in rules) out = re.replace(out, token)
        return out.replace(Regex("\\s+"), " ")
    }
}

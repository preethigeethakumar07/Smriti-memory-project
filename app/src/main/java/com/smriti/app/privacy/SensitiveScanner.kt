package com.smriti.app.privacy

/**
 * Spots sensitive things the camera has just seen, names the KIND, and returns only a MASKED preview
 * ("4••• •••• •••• ••••"). The real value is never returned, logged, stored or sent to the dashboard: the text
 * that is saved to memory is separately cleaned by [TextScrubber].
 *
 * Face pixels are blurred in the picture before any model runs; the scanner only counts them.
 */
enum class SensitiveKind(val label: String, val action: String) {
    FACE("Face", "blurred in the picture before analysis"),
    CARD("Card number", "removed from text before saving"),
    ID_NUMBER("ID number", "removed from text before saving"),
    PHONE("Phone number", "removed from text before saving"),
    EMAIL("Email address", "removed from text before saving"),
    PLATE("Number plate", "removed from text before saving"),
    PERSON("Person's name", "masked, not saved"),
    SECRET("Password or one-time code", "removed from text before saving"),
    DOCUMENT("ID or financial document", "text kept only with identifiers removed"),
    SCREEN("Screen showing text", "text kept only with identifiers removed")
}

data class SensitiveFinding(val kind: SensitiveKind, val masked: String)

object SensitiveScanner {

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val plate = Regex("\\b[A-Z]{2}[ -]?\\d{1,2}[ -]?[A-Z]{1,3}[ -]?\\d{3,4}\\b")
    private val digitRun = Regex("\\b(?:\\d[ -]?){12,19}\\b")
    private val mobile = Regex("(?<!\\d)(?:\\+?91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)")
    private val secret = Regex("(?i)\\b(otp|password|passcode|pin|cvv|cvc)\\b\\D{0,8}([A-Za-z0-9@#$%!]{3,14})")
    private val personTitle = Regex("\\b(?:Mr|Mrs|Ms|Dr|Shri|Smt|Sri)\\.?\\s+([A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,}){0,2})")
    // the label is case-insensitive, but the name itself must be Capitalised Words, so "OTP 4829" is not swallowed as a name
    private val personField = Regex("\\b(?i:name|holder|candidate|father'?s name)\\s*[:\\-]\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})")
    private val documentWords = Regex("(?i)\\b(aadhaar|aadhar|passport|driving licen[cs]e|permanent account|income tax|ifsc|account (?:no|number)|date of birth|dob|government of india|debit card|credit card|valid thru|expiry)\\b")
    private val screenLabels = setOf("tv", "laptop", "cell phone", "mobile phone", "monitor", "tablet", "computer", "screen", "display")

    fun scan(ocrText: String, faces: Int, objectLabels: Collection<String>): List<SensitiveFinding> {
        val out = ArrayList<SensitiveFinding>()
        if (faces > 0) out += SensitiveFinding(SensitiveKind.FACE, "$faces blurred")
        val text = ocrText.replace('\n', ' ')
        if (text.isBlank()) return out

        for (m in email.findAll(text)) out += SensitiveFinding(SensitiveKind.EMAIL, maskEmail(m.value))
        for (m in plate.findAll(text)) out += SensitiveFinding(SensitiveKind.PLATE, mask(m.value, keep = 2))

        val seen = HashSet<String>()
        for (m in digitRun.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (!seen.add(digits)) continue
            val kind = when {
                digits.length in 13..19 && luhn(digits) -> SensitiveKind.CARD
                digits.length == 12 -> SensitiveKind.ID_NUMBER
                else -> continue
            }
            out += SensitiveFinding(kind, maskDigits(m.value))
        }
        for (m in mobile.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (seen.add(digits)) out += SensitiveFinding(SensitiveKind.PHONE, maskDigits(m.value))
        }
        for (m in secret.findAll(text)) out += SensitiveFinding(SensitiveKind.SECRET, m.groupValues[1].lowercase() + ": " + mask(m.groupValues[2], keep = 0))
        for (m in personTitle.findAll(text)) out += SensitiveFinding(SensitiveKind.PERSON, maskName(m.groupValues[1]))
        for (m in personField.findAll(text)) out += SensitiveFinding(SensitiveKind.PERSON, maskName(m.groupValues[1]))

        documentWords.find(text)?.let { out += SensitiveFinding(SensitiveKind.DOCUMENT, "contains \"${it.value.lowercase()}\"") }
        if (text.length >= 20 && objectLabels.any { it.lowercase() in screenLabels }) out += SensitiveFinding(SensitiveKind.SCREEN, "${text.length} characters visible")
        return out.distinctBy { it.kind to it.masked }
    }

    // ---- masking: first character stays, everything else becomes a bullet ------------------------------------

    fun mask(value: String, keep: Int = 1): String =
        value.mapIndexed { i, c -> if (c == ' ' || c == '-' || i < keep) c else '•' }.joinToString("")

    /** "9876543210" -> "9•••••••••", with the grouping kept so the shape is readable. */
    fun maskDigits(value: String): String {
        var shown = 0
        return value.map { c -> if (c.isDigit()) { if (shown++ == 0) c else '•' } else c }.joinToString("")
    }

    fun maskEmail(value: String): String {
        val at = value.indexOf('@')
        if (at <= 0) return mask(value)
        val dot = value.lastIndexOf('.')
        return mask(value.substring(0, at)) + "@" + mask(value.substring(at + 1, dot.coerceAtLeast(at + 1))) + value.substring(dot.coerceAtLeast(at + 1))
    }

    /** "Dhanesh Kumar" -> "D•••••• K•••••" */
    fun maskName(value: String): String = value.trim().split(Regex("\\s+")).joinToString(" ") { mask(it) }

    internal fun luhn(digits: String): Boolean {
        var sum = 0
        var alt = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alt) { n *= 2; if (n > 9) n -= 9 }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}

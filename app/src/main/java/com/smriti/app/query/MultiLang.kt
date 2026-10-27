package com.smriti.app.query

/**
 * Understanding spoken questions in the Indian languages Smriti offers. The recogniser returns the sentence in that
 * language (or in Latin letters, as many people speak Hindi: "mera phone kahan hai"). Instead of translating whole
 * sentences, the router looks for the few words that carry the meaning:
 *   a "where" word + a thing (or just the thing)  -> Where(item in English)
 *   "what do you see"                              -> Live
 *   "forget the last N minutes"                    -> Forget
 *   "what happened in the last N minutes"          -> Range
 *   greetings                                      -> Help
 * Things Smriti has seen are stored with English names, so the native word is mapped to the English one.
 *
 * Word lists are deliberately short. Anything not recognised falls through to the English router.
 */
object MultiLang {

    private class Pack(
        val where: List<String>,
        val live: List<String>,
        val forget: List<String>,
        val happened: List<String>,
        val minute: List<String>,
        val hour: List<String>,
        val highlight: List<String>,
        val hello: List<String>,
        /** english=native|native|... */
        val objects: String
    ) {
        /** native word -> english, longest word first so "mobile phone" beats "phone" */
        val lookup: List<Pair<String, String>> = objects.lines().map { it.trim() }.filter { it.contains('=') }.flatMap { line ->
            val (en, natives) = line.split('=', limit = 2)
            natives.split('|').map { it.trim().lowercase() to en.trim() }
        }.sortedByDescending { it.first.length }
        val reverse: Map<String, String> = lookup.groupBy({ it.second }, { it.first }).mapValues { (_, v) -> v.first() }
    }

    // ---- Hindi (Devanagari and the Latin spelling people also use) ---------------------------------------------------
    private val HI = Pack(
        where = listOf("कहाँ", "कहां", "कहा है", "कहाँ है", "ढूंढ", "ढूँढ", "खोज", "खो गया", "खो गई", "मिल नहीं", "kahan", "kahaan", "kaha hai", "kahan hai", "dhundh", "dhoondh", "khoj", "kho gaya", "kho gayi", "dikha do"),
        live = listOf("क्या देख", "क्या दिख", "क्या दिखाई", "सामने क्या", "क्या है सामने", "वर्णन", "बताओ क्या", "kya dekh", "kya dikh", "kya dikhai", "saamne kya", "samne kya", "kya nazar", "batao kya"),
        forget = listOf("भूल जा", "भूल जाओ", "मिटा", "हटा दो", "bhool jao", "bhul jao", "mita do", "mita"),
        happened = listOf("क्या हुआ", "क्या हुआ था", "क्या हो रहा", "सारांश", "kya hua", "kya hua tha", "saransh"),
        minute = listOf("मिनट", "minute", "min"),
        hour = listOf("घंटे", "घंटा", "ghante", "ghanta", "hour"),
        highlight = listOf("सबसे महत्वपूर्ण", "सबसे ज़रूरी", "सबसे खास", "sabse important", "sabse zaroori", "sabse khas"),
        hello = listOf("नमस्ते", "नमस्कार", "हेलो", "हैलो", "हाय", "namaste", "namaskar", "hello", "hi"),
        objects = """
            keys=चाबी|चाबियां|चाबियाँ|चाबियों|चाभी|कुंजी|chabi|chaabi|chabiyan|chabiyaan|chabiya
            phone=फोन|फ़ोन|मोबाइल|मोबाइल फोन|मोबाईल|phone|mobile
            wallet=पर्स|बटुआ|वॉलेट|purse|batua|wallet
            glasses=चश्मा|चश्मे|चश्मा|chashma|chasma|glasses
            pill=दवाई|दवा|गोली|गोलियां|गोलियाँ|टैबलेट|दवाइयां|dawai|dawa|goli|tablet|medicine
            remote=रिमोट|remote
            laptop=लैपटॉप|लैपटाप|laptop
            bottle=बोतल|बॉटल|bottle|botal
            cup=कप|प्याला|cup
            bag=बैग|थैला|बस्ता|bag|thaila
            book=किताब|पुस्तक|book|kitab
            mouse=माउस|mouse
            keyboard=कीबोर्ड|keyboard
            charger=चार्जर|charger
            tv=टीवी|टेलीविज़न|टेलीविजन|tv|television
            chair=कुर्सी|chair|kursi
            table=मेज़|मेज|टेबल|table|mez
            umbrella=छाता|chhata|chata
            watch=घड़ी|घड़ी|watch|ghadi
            headphones=हेडफोन|हेडफ़ोन|ईयरफोन|earphone|headphone
            notebook=कॉपी|नोटबुक|notebook|copy
        """.trimIndent()
    )

    private val TA = Pack(
        where = listOf("எங்கே", "எங்க", "தேடு", "காணவில்லை", "எங்கு", "enge", "enga"),
        live = listOf("என்ன தெரிகிறது", "என்ன பார்க்கிறாய்", "என்ன பார்க்கிற", "என்ன இருக்கிறது", "விவரி"),
        forget = listOf("மறந்துவிடு", "அழி", "மறந்திடு"),
        happened = listOf("என்ன நடந்தது", "சுருக்கம்"),
        minute = listOf("நிமிட", "நிமிடம்"),
        hour = listOf("மணி நேர"),
        highlight = listOf("மிக முக்கியமான"),
        hello = listOf("வணக்கம்", "ஹலோ"),
        objects = """
            keys=சாவி|சாவிகள்|சாவியை|சாவிக்
            phone=போன்|தொலைபேசி|மொபைல்|செல்போன்
            wallet=பர்ஸ்|பணப்பை|வாலட்
            glasses=கண்ணாடி|கண்ணாடிகள்
            pill=மாத்திரை|மாத்திரைகள்|மருந்து|டேப்லெட்
            remote=ரிமோட்
            laptop=லேப்டாப்
            bottle=பாட்டில்|புட்டி
            cup=கப்|குவளை
            bag=பை|பேக்
            book=புத்தகம்
            mouse=மவுஸ்
            keyboard=கீபோர்டு|கீ போர்டு
            charger=சார்ஜர்
            tv=டிவி|தொலைக்காட்சி
            chair=நாற்காலி|சேர்
            table=மேசை|டேபிள்
            watch=கடிகாரம்|வாட்ச்
        """.trimIndent()
    )

    private val TE = Pack(
        where = listOf("ఎక్కడ", "వెతుకు", "కనిపించడం లేదు", "ekkada"),
        live = listOf("ఏమి కనిపిస్తోంది", "ఏం కనిపిస్తోంది", "ఏం చూస్తున్నావు", "వివరించు"),
        forget = listOf("మర్చిపో", "తొలగించు", "చెరిపేయి"),
        happened = listOf("ఏమి జరిగింది", "ఏం జరిగింది", "సారాంశం"),
        minute = listOf("నిమిషాల", "నిమిషం"),
        hour = listOf("గంట"),
        highlight = listOf("అత్యంత ముఖ్యమైన"),
        hello = listOf("నమస్తే", "హలో"),
        objects = """
            keys=తాళం చెవి|తాళాలు|తాళం|కీ|కీస్
            phone=ఫోన్|మొబైల్|సెల్ ఫోన్
            wallet=పర్సు|వాలెట్
            glasses=కళ్ళజోడు|అద్దాలు|కళ్లద్దాలు
            pill=మాత్రలు|మాత్ర|మందు|టాబ్లెట్
            remote=రిమోట్
            laptop=ల్యాప్‌టాప్|లాప్టాప్
            bottle=బాటిల్|సీసా
            cup=కప్పు|కప్
            bag=బ్యాగ్|సంచి
            book=పుస్తకం|బుక్
            mouse=మౌస్
            keyboard=కీబోర్డ్
            charger=ఛార్జర్|చార్జర్
            tv=టీవీ|టెలివిజన్
            chair=కుర్చీ
            table=బల్ల|టేబుల్
            watch=గడియారం|వాచ్
        """.trimIndent()
    )

    private val KN = Pack(
        where = listOf("ಎಲ್ಲಿ", "ಹುಡುಕು", "ಕಾಣುತ್ತಿಲ್ಲ", "elli"),
        live = listOf("ಏನು ಕಾಣುತ್ತಿದೆ", "ಏನು ಕಾಣಿಸುತ್ತಿದೆ", "ಏನು ನೋಡುತ್ತಿದ್ದೀಯ", "ವಿವರಿಸು"),
        forget = listOf("ಮರೆತುಬಿಡು", "ಅಳಿಸು", "ಮರೆತುಬಿಡಿ"),
        happened = listOf("ಏನಾಯಿತು", "ಸಾರಾಂಶ"),
        minute = listOf("ನಿಮಿಷ"),
        hour = listOf("ಗಂಟೆ"),
        highlight = listOf("ಅತ್ಯಂತ ಮುಖ್ಯವಾದ"),
        hello = listOf("ನಮಸ್ಕಾರ", "ಹಲೋ"),
        objects = """
            keys=ಕೀ|ಕೀಲಿ|ಕೀಲಿಕೈ|ಬೀಗದ ಕೈ|ಕೀಗಳು
            phone=ಫೋನ್|ಮೊಬೈಲ್
            wallet=ಪರ್ಸ್|ವ್ಯಾಲೆಟ್
            glasses=ಕನ್ನಡಕ
            pill=ಮಾತ್ರೆ|ಮಾತ್ರೆಗಳು|ಔಷಧ|ಟ್ಯಾಬ್ಲೆಟ್
            remote=ರಿಮೋಟ್
            laptop=ಲ್ಯಾಪ್‌ಟಾಪ್|ಲ್ಯಾಪ್ಟಾಪ್
            bottle=ಬಾಟಲ್|ಬಾಟಲಿ
            cup=ಕಪ್
            bag=ಬ್ಯಾಗ್|ಚೀಲ
            book=ಪುಸ್ತಕ|ಬುಕ್
            mouse=ಮೌಸ್
            keyboard=ಕೀಬೋರ್ಡ್
            charger=ಚಾರ್ಜರ್
            tv=ಟಿವಿ|ದೂರದರ್ಶನ
            chair=ಕುರ್ಚಿ
            table=ಮೇಜು|ಟೇಬಲ್
            watch=ಗಡಿಯಾರ|ವಾಚ್
        """.trimIndent()
    )

    private val ML = Pack(
        where = listOf("എവിടെ", "തിരയൂ", "കാണുന്നില്ല", "evide"),
        live = listOf("എന്താണ് കാണുന്നത്", "എന്ത് കാണുന്നു", "എന്താണ് കാണുന്നുണ്ട്", "വിവരിക്കൂ"),
        forget = listOf("മറക്കൂ", "മായ്ക്കൂ", "മറന്നേക്കൂ"),
        happened = listOf("എന്ത് സംഭവിച്ചു", "എന്താണ് സംഭവിച്ചത്", "സംഗ്രഹം"),
        minute = listOf("മിനിറ്റ്", "മിനിറ്റിൽ"),
        hour = listOf("മണിക്കൂർ"),
        highlight = listOf("ഏറ്റവും പ്രധാനപ്പെട്ട"),
        hello = listOf("നമസ്കാരം", "ഹലോ"),
        objects = """
            keys=താക്കോൽ|താക്കോലുകൾ|കീ
            phone=ഫോൺ|മൊബൈൽ
            wallet=പേഴ്സ്|വാലറ്റ്
            glasses=കണ്ണട|കണ്ണടകൾ
            pill=ഗുളിക|ഗുളികകൾ|മരുന്ന്|ടാബ്‌ലെറ്റ്
            remote=റിമോട്ട്
            laptop=ലാപ്ടോപ്പ്|ലാപ്‌ടോപ്പ്
            bottle=കുപ്പി|ബോട്ടിൽ
            cup=കപ്പ്
            bag=ബാഗ്|സഞ്ചി
            book=പുസ്തകം|ബുക്ക്
            mouse=മൗസ്
            keyboard=കീബോർഡ്
            charger=ചാർജർ
            tv=ടിവി|ടെലിവിഷൻ
            chair=കസേര
            table=മേശ|ടേബിൾ
            watch=വാച്ച്|കൈക്കടികാരം
        """.trimIndent()
    )

    private val MR = Pack(
        where = listOf("कुठे", "कुठं", "शोध", "सापडत नाही", "kuthe"),
        live = listOf("काय दिसत आहे", "काय दिसतंय", "काय दिसते", "काय पाहत आहेस", "वर्णन"),
        forget = listOf("विसर", "पुसून टाक", "विसरून जा"),
        happened = listOf("काय झाले", "काय झालं", "सारांश"),
        minute = listOf("मिनिट", "मिनिटे"),
        hour = listOf("तास"),
        highlight = listOf("सर्वात महत्त्वाचा", "सर्वात महत्वाचा"),
        hello = listOf("नमस्कार", "हॅलो", "नमस्ते"),
        objects = """
            keys=चावी|चाव्या|किल्ली|किल्ल्या
            phone=फोन|मोबाइल|मोबाईल
            wallet=पाकीट|पर्स|वॉलेट
            glasses=चष्मा
            pill=गोळी|गोळ्या|औषध|टॅबलेट
            remote=रिमोट
            laptop=लॅपटॉप
            bottle=बाटली|बॉटल
            cup=कप|पेला
            bag=बॅग|पिशवी
            book=पुस्तक|वही
            mouse=माउस
            keyboard=कीबोर्ड
            charger=चार्जर
            tv=टीव्ही|दूरदर्शन
            chair=खुर्ची
            table=टेबल|मेज
            watch=घड्याळ|वॉच
        """.trimIndent()
    )

    private val BN = Pack(
        where = listOf("কোথায়", "কোথা", "খুঁজ", "খুজ", "পাচ্ছি না", "kothay"),
        live = listOf("কী দেখছ", "কি দেখছ", "কী দেখা যাচ্ছে", "কি দেখা যাচ্ছে", "বর্ণনা"),
        forget = listOf("ভুলে যাও", "মুছে দাও", "মুছে ফেল"),
        happened = listOf("কী হয়েছিল", "কি হয়েছিল", "সারাংশ"),
        minute = listOf("মিনিট"),
        hour = listOf("ঘণ্টা", "ঘন্টা"),
        highlight = listOf("সবচেয়ে গুরুত্বপূর্ণ"),
        hello = listOf("নমস্কার", "হ্যালো", "হাই"),
        objects = """
            keys=চাবি|চাবিগুলো|চাবিটা
            phone=ফোন|মোবাইল
            wallet=মানিব্যাগ|পার্স|ওয়ালেট
            glasses=চশমা
            pill=ওষুধ|ট্যাবলেট|বড়ি
            remote=রিমোট
            laptop=ল্যাপটপ
            bottle=বোতল|বটল
            cup=কাপ
            bag=ব্যাগ|থলে
            book=বই
            mouse=মাউস
            keyboard=কিবোর্ড|কীবোর্ড
            charger=চার্জার
            tv=টিভি|টেলিভিশন
            chair=চেয়ার
            table=টেবিল
            watch=ঘড়ি|ওয়াচ
        """.trimIndent()
    )

    private val GU = Pack(
        where = listOf("ક્યાં", "ક્યા", "શોધ", "મળતું નથી", "kyan"),
        live = listOf("શું દેખાય છે", "શું જુએ છે", "શું જોઈ રહ્યા", "વર્ણન"),
        forget = listOf("ભૂલી જા", "ભૂલી જાવ", "ભૂંસી નાખ", "કાઢી નાખ"),
        happened = listOf("શું થયું", "સારાંશ"),
        minute = listOf("મિનિટ"),
        hour = listOf("કલાક"),
        highlight = listOf("સૌથી મહત્વનો", "સૌથી મહત્વની"),
        hello = listOf("નમસ્તે", "હેલો"),
        objects = """
            keys=ચાવી|ચાવીઓ|ચાવીઓ
            phone=ફોન|મોબાઇલ|મોબાઈલ
            wallet=પાકીટ|પર્સ|વૉલેટ
            glasses=ચશ્મા
            pill=ગોળી|ગોળીઓ|દવા|ટેબલેટ
            remote=રિમોટ
            laptop=લેપટોપ
            bottle=બોટલ|શીશી
            cup=કપ
            bag=બેગ|થેલી
            book=પુસ્તક|ચોપડી
            mouse=માઉસ
            keyboard=કીબોર્ડ
            charger=ચાર્જર
            tv=ટીવી|ટેલિવિઝન
            chair=ખુરશી
            table=ટેબલ|મેજ
            watch=ઘડિયાળ|વૉચ
        """.trimIndent()
    )

    private val packs: Map<String, Pack> = mapOf("hi" to HI, "ta" to TA, "te" to TE, "kn" to KN, "ml" to ML, "mr" to MR, "bn" to BN, "gu" to GU)

    fun supported(tag: String) = tag.take(2).lowercase() in packs

    /** English item name -> the word to say in [tag]'s language, or the English name when unknown. */
    fun nativeName(english: String, tag: String): String = packs[tag.take(2).lowercase()]?.reverse?.get(english) ?: english

    /** Returns null for English text or when nothing in the sentence is recognised (the English router then tries). */
    fun parse(raw: String, tag: String, nowMs: Long = System.currentTimeMillis()): Intent? {
        val pack = packs[tag.take(2).lowercase()] ?: return null
        val q = digits(raw.lowercase().trim().trimEnd('?', '.', '!', '।', ','))
        if (q.isEmpty()) return null
        val has = { words: List<String> -> words.any { q.contains(it.lowercase()) } }

        if (has(pack.forget)) return Intent.Forget(minutes(q, pack) ?: 5)
        if (has(pack.live)) return Intent.Live
        if (has(pack.highlight)) return Intent.Highlight
        if (has(pack.happened) || (minutes(q, pack) != null && !has(pack.where))) {
            val m = minutes(q, pack) ?: 10
            return Intent.Range(nowMs - m * 60_000L, nowMs, "the last $m minutes")
        }

        val item = pack.lookup.firstOrNull { q.contains(it.first) }?.second
        if (has(pack.where) || item != null) {
            if (item != null) return Intent.Where(item)
            // an English word inside the sentence ("mera laptop kahan hai"): use it as the thing to find
            latinItem(q)?.let { return Intent.Where(it) }
            return null
        }
        if (pack.hello.any { q == it.lowercase() || q.startsWith(it.lowercase() + " ") }) return Intent.Help
        return null
    }

    private val fillers = setOf(
        "mera", "meri", "mere", "kahan", "kahaan", "kaha", "hai", "hain", "ka", "ki", "ke", "kya", "aap", "ne", "tum", "tumne", "dekha", "dikha", "do", "batao", "bata",
        "please", "smriti", "hey", "hi", "ok", "okay", "aur", "mujhe", "dhundh", "dhoondh", "kho", "gaya", "gayi", "the", "my", "where", "is", "are", "you", "see"
    )

    private fun latinItem(q: String): String? {
        val words = Regex("[a-z]{3,}").findAll(q).map { it.value }.filter { it !in fillers }.toList()
        return words.firstOrNull()
    }

    /** Native digits to ASCII, so "पिछले ५ मिनट" and "last 5 minutes" read the same. */
    private fun digits(s: String): String = buildString {
        for (c in s) {
            val d = Character.digit(c, 10)
            if (d in 0..9 && !c.isAsciiDigit()) append(('0' + d)) else append(c)
        }
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun minutes(q: String, pack: Pack): Int? {
        val n = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull() ?: return null
        val isHour = pack.hour.any { q.contains(it.lowercase()) }
        val isMinute = pack.minute.any { q.contains(it.lowercase()) }
        return when { isHour -> n * 60; isMinute -> n; else -> null }
    }
}

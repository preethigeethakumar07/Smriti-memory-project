package com.smriti.app.ai

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Turn on the flashlight", "email Ravi about the meeting", "add dentist to my calendar tomorrow at 4": the small FunctionGemma
 * model (MobileActions-270M, the one in Google AI Edge Gallery) turns the sentence into a function call, and the call is
 * carried out here with ordinary Android intents.
 *
 * The seven functions the model was trained on are the same ones the Gallery's Mobile Actions task offers, with the same names
 * and arguments: turnOnFlashlight, turnOffFlashlight, createContact, sendEmail, showLocationOnMap, openWifiSettings,
 * createCalendarEvent. Things that open another app (email, calendar, contact, dialer, messages, gallery) always stop at that app's
 * own screen, pre-filled, so the user presses send / save / call. Nothing is sent or dialled by Smriti itself.
 * Timer, alarm, Bluetooth, dialer, messages and Photos are read by plain rules, because that model was not trained on them.
 */
class Actions(private val context: Context) {

    sealed interface State {
        data object NotLoaded : State
        data class Missing(val expected: String) : State
        data class Ready(val loadMs: Long) : State
        data class Failed(val reason: String) : State
    }

    @Volatile var state: State = State.NotLoaded
        private set
    @Volatile var lastError: String? = null
        private set

    private var engine: Engine? = null
    private val one = Mutex()

    fun modelFile(): File = File(File(context.filesDir, "models"), "mobile_actions.litertlm")

    @Synchronized
    fun load(): State {
        if (state is State.Ready) return state
        val f = modelFile()
        if (!f.exists()) { state = State.Missing(f.absolutePath); return state }
        val t0 = SystemClock.elapsedRealtime()
        state = try {
            val e = Engine(EngineConfig(modelPath = f.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
            e.initialize()
            engine = e
            State.Ready(SystemClock.elapsedRealtime() - t0).also { AiMeter.loaded("FunctionGemma 270M", "phone actions", "CPU", it.loadMs) }
        } catch (t: Throwable) {
            State.Failed("${t.javaClass.simpleName}: ${t.message?.take(200)}")
        }
        return state
    }

    /** True when the sentence is asking the phone to do something, so ordinary questions never reach this code. */
    fun looksLikeAction(q: String) = TRIGGER.containsMatchIn(q)

    /** Runs the request. Returns what was done in one sentence, or null when nothing here can do it. */
    suspend fun run(utterance: String): String? = one.withLock {
        withContext(Dispatchers.Default) {
            val done = ArrayList<String>()
            val tools = DeviceTools(context, done, utterance)
            // things the model was not trained on: read by rules, always the same result
            if (ruleBased(utterance, tools)) return@withContext tools.result()

            val e = engine
            if (e != null) {
                try {
                    val now = SimpleDateFormat("EEEE, yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                    e.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of("You are a model that can do function calling with the following functions. The current date and time is $now."),
                            tools = listOf(tool(tools))
                        )
                    ).use { c -> AiMeter.time("FunctionGemma 270M", "phone actions", "CPU") { c.sendMessage(utterance) } }
                    lastError = null
                } catch (t: Throwable) {
                    lastError = "${t.javaClass.simpleName}: ${t.message?.take(300)}"
                    PipelineBus.warn("actions", "FunctionGemma failed: $lastError")
                }
            }
            // the model produced no call: read the plain ones directly so the obvious commands always work
            if (done.isEmpty()) fallback(utterance, tools)
            if (done.isEmpty()) null else tools.result()
        }
    }

    private fun ruleBased(q: String, t: DeviceTools): Boolean {
        val s = q.lowercase()
        when {
            Regex("\\b(open|show|go to|launch|check)\\b.*\\bcalendar\\b").containsMatchIn(s) && !Regex("\\b(add|create|schedule|put|book)\\b").containsMatchIn(s) -> t.openCalendar()
            Regex("\\b(open|show|go to|launch)\\b.*\\b(alarms|clock)\\b").containsMatchIn(s) && !Regex("\\d").containsMatchIn(s) -> t.showAlarms()
            Regex("^\\W*(open|launch|show)( me)?( the)? maps?\\W*$").containsMatchIn(s) -> t.openMaps()
            Regex("\\btimer\\b").containsMatchIn(s) -> {
                val m = Regex("(\\d+|a|an|one|two|three|four|five|ten|fifteen|twenty|thirty)\\s*(second|sec|minute|min|hour|hr)").find(s) ?: return false
                val n = m.groupValues[1].toIntOrNull() ?: WORDS[m.groupValues[1]] ?: 1
                t.setTimer(when { m.groupValues[2].startsWith("h") -> n * 3600; m.groupValues[2].startsWith("m") -> n * 60; else -> n })
            }
            Regex("\\balarm\\b").containsMatchIn(s) -> {
                val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(s) ?: return false
                var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val mer = m.groupValues[3].replace(".", "")
                if (mer == "pm" && h < 12) h += 12 else if (mer == "am" && h == 12) h = 0
                t.setAlarm(h, min)
            }
            "bluetooth" in s -> t.openBluetoothSettings()
            Regex("\\b(photos?|gallery|pictures|albums?)\\b").containsMatchIn(s) && Regex("\\b(open|show|go to|launch|take me)\\b").containsMatchIn(s) -> t.openGallery()
            Regex("\\b(text|message|sms|whatsapp)\\b").containsMatchIn(s) && Regex("\\b(send|write|compose|text)\\b").containsMatchIn(s) && !Regex("\\bemail\\b").containsMatchIn(s) -> {
                val num = Regex("(\\+?\\d[\\d ]{6,14}\\d)").find(s)?.groupValues?.get(1)?.replace(" ", "")
                val body = Regex("\\b(?:saying|that says|message)\\s+(.+)$").find(q)?.groupValues?.get(1)
                t.composeMessage(num, body)
            }
            Regex("\\b(call|dial|phone|ring)\\b").containsMatchIn(s) && Regex("\\d{5,}").containsMatchIn(s.replace(" ", "")) -> t.openDialer(Regex("(\\+?\\d[\\d ]{4,14}\\d)").find(s)?.groupValues?.get(1)?.replace(" ", ""))
            Regex("\\b(call|dial|ring)\\b").containsMatchIn(s) -> t.openDialer(null)
            else -> return false
        }
        return true
    }

    private fun fallback(q: String, t: DeviceTools) {
        val s = q.lowercase()
        when {
            Regex("(flash ?light|torch)").containsMatchIn(s) -> if (Regex("\\b(off|disable|stop)\\b").containsMatchIn(s)) t.turnOffFlashlight() else t.turnOnFlashlight()
            "wifi" in s || "wi-fi" in s -> t.openWifiSettings()
            Regex("\\b(calendar|appointment|meeting|remind me|schedule)\\b").containsMatchIn(s) ->
                eventFromWords(q)?.let { (title, start) -> t.createCalendarEvent(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(start)), title) }
            Regex("\\b(map|navigate to|directions to)\\b").containsMatchIn(s) ->
                Regex("(?:map|navigate to|directions to|show|open)\\s+(?:me\\s+)?(?:the\\s+)?(.+?)(?:\\s+on\\s+(?:the\\s+)?maps?)?$").find(s)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { t.showLocationOnMap(it) }
        }
    }

    fun close() { runCatching { engine?.close() }; engine = null; state = State.NotLoaded }

    /** The functions the model may call. The first seven match the Gallery's Mobile Actions task exactly. */
    class DeviceTools(private val ctx: Context, private val done: MutableList<String>, private val said: String = "") : ToolSet {

        @Tool(description = "Turns the flashlight on")
        fun turnOnFlashlight(): Map<String, Any> = torch(true)

        @Tool(description = "Turns the flashlight off")
        fun turnOffFlashlight(): Map<String, Any> = torch(false)

        @Tool(description = "Creates a contact in the phone's contacts list")
        fun createContact(
            @ToolParam(description = "The first name of the contact") firstName: String,
            @ToolParam(description = "The last name of the contact") lastName: String,
            @ToolParam(description = "The phone number of the contact") phoneNumber: String,
            @ToolParam(description = "The email address of the contact") email: String
        ): Map<String, Any> {
            go(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName".trim())
                .putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
                .putExtra(ContactsContract.Intents.Insert.EMAIL, email))
            done += "Opened a new contact for ${"$firstName $lastName".trim()}. Press save to keep it."
            return ok()
        }

        @Tool(description = "Sends an email")
        fun sendEmail(
            @ToolParam(description = "The email address of the recipient") to: String,
            @ToolParam(description = "The subject of the email") subject: String,
            @ToolParam(description = "The body of the email") body: String
        ): Map<String, Any> {
            go(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(to)).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body))
            done += "Opened an email to $to. Press send when you are happy with it."
            return ok()
        }

        @Tool(description = "Shows a location on the map")
        fun showLocationOnMap(@ToolParam(description = "The location to search for. May be the name of a place, a business, or an address.") location: String): Map<String, Any> {
            val place = placeFromWords(location)
            go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}")))
            done += "Showing $place on the map."
            return ok()
        }

        @Tool(description = "Opens the Wi-Fi settings")
        fun openWifiSettings(): Map<String, Any> { go(Intent(Settings.ACTION_WIFI_SETTINGS)); done += "Opened Wi-Fi settings."; return ok() }

        @Tool(description = "Creates a new calendar event")
        fun createCalendarEvent(
            @ToolParam(description = "The date and time of the event in the format YYYY-MM-DDTHH:MM:SS") datetime: String,
            @ToolParam(description = "The title of the event") title: String
        ): Map<String, Any> {
            val modelStart = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(datetime)?.time }.getOrNull() ?: (System.currentTimeMillis() + 3_600_000L)
            // the small model keeps the time well but gets day arithmetic wrong ("tomorrow" came back a week out): the day is read from the words
            val start = Actions.dayFromWords(said, modelStart)
            go(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000L))
            done += "Opened a calendar event \"$title\" for ${SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(start))}. Press save to keep it."
            return ok()
        }

        // ---- not in the model's training: called by rules only -------------------------------------------------------

        fun setTimer(seconds: Int) {
            go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400)).putExtra(AlarmClock.EXTRA_SKIP_UI, false))
            val m = seconds / 60
            done += "Timer set for ${if (seconds >= 60) "$m minute${if (m == 1) "" else "s"}" else "$seconds seconds"}."
        }

        fun setAlarm(hour: Int, minutes: Int) {
            go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23)).putExtra(AlarmClock.EXTRA_MINUTES, minutes.coerceIn(0, 59)))
            val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23)); set(Calendar.MINUTE, minutes.coerceIn(0, 59)) }
            done += "Alarm set for ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(c.time)}."
        }

        fun openCalendar() { go(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}"))); done += "Opened your calendar." }
        fun showAlarms() { go(Intent(AlarmClock.ACTION_SHOW_ALARMS)); done += "Opened your alarms." }
        fun openMaps() { go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))); done += "Opened maps." }

        fun openBluetoothSettings() { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); done += "Opened Bluetooth settings." }

        /** Photos / Gallery: opens the phone's own picture viewer. Smriti never reads the user's photos. */
        fun openGallery() {
            val tried = listOf(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
                Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            ).any { launch(it) }
            done += if (tried) "Opened your photos." else "I couldn't find a photos app."
        }

        /** Opens the dialer with the number filled in; the user presses the call button. */
        fun openDialer(number: String?) {
            go(Intent(Intent.ACTION_DIAL, if (number.isNullOrBlank()) null else Uri.parse("tel:$number")))
            done += if (number.isNullOrBlank()) "Opened the dialer. I can't look up contacts by name yet, so say or type the number." else "Opened the dialer with $number. Press call to place it."
        }

        /** Opens the messages app with the text ready; the user presses send. */
        fun composeMessage(number: String?, body: String?) {
            go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${number.orEmpty()}")).putExtra("sms_body", body.orEmpty()))
            done += "Opened a message${number?.let { " to $it" } ?: ""}. Press send when you are ready."
        }

        private fun torch(on: Boolean): Map<String, Any> {
            val okay = (torchHook?.invoke(on) == true) || runCatching {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                cm.setTorchMode(id, on)
            }.isSuccess
            done += if (okay) "Flashlight ${if (on) "on" else "off"}." else "I couldn't reach the flashlight right now."
            return mapOf("result" to if (okay) "success" else "failed")
        }

        private fun ok() = mapOf<String, Any>("result" to "success")

        /** set when an app could not be opened, so the answer says so instead of claiming it worked */
        var failed = false
            private set
        fun result(): String? = if (failed) "I could not open that on this phone." else done.joinToString(" ").ifBlank { null }

        private fun launch(i: Intent): Boolean = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { PipelineBus.warn("actions", "could not open ${i.action}: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }.isSuccess
        private fun go(i: Intent): Boolean = launch(i).also { if (!it) failed = true }
    }

    companion object {
        /** set by the app: turns the torch on through the open camera, true if it could */
        @Volatile var torchHook: ((Boolean) -> Boolean)? = null

        /**
         * Keeps the time of day from [modelStart] and sets the day from words in what the user said: today, tonight, tomorrow,
         * a weekday name, "next week". When the words give no day, the model's own date is kept.
         */
        fun dayFromWords(said: String, modelStart: Long, now: Long = System.currentTimeMillis()): Long {
            val s = said.lowercase()
            val time = Calendar.getInstance().apply { timeInMillis = modelStart }
            val day = Calendar.getInstance().apply { timeInMillis = now }
            val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
            val named = days.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(s) }
            when {
                Regex("\\b(day after tomorrow)\\b").containsMatchIn(s) -> day.add(Calendar.DAY_OF_YEAR, 2)
                Regex("\\btomorrow\\b").containsMatchIn(s) -> day.add(Calendar.DAY_OF_YEAR, 1)
                Regex("\\b(today|tonight|this evening|this afternoon)\\b").containsMatchIn(s) -> {}
                named >= 0 -> {
                    var add = (named + 1 - day.get(Calendar.DAY_OF_WEEK) + 7) % 7
                    if (add == 0) add = 7
                    if (Regex("\\bnext\\b").containsMatchIn(s) && add < 7) add += 0
                    day.add(Calendar.DAY_OF_YEAR, add)
                }
                Regex("\\bnext week\\b").containsMatchIn(s) -> day.add(Calendar.DAY_OF_YEAR, 7)
                else -> return modelStart
            }
            day.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY)); day.set(Calendar.MINUTE, time.get(Calendar.MINUTE))
            day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0)
            return day.timeInMillis
        }

        /** "show Marina Beach on the map" or "navigate to the chemist" gives the place alone: "Marina Beach", "chemist" */
        fun placeFromWords(said: String): String {
            val t = said.trim().trim('.', '!', '?', '\'', '"', ' ')
                .replace(Regex("^(?:please\\s+|can you\\s+|could you\\s+)*(?:show|open|find|navigate to|directions to|take me to|get me to|search for)\\s+(?:me\\s+)?(?:the\\s+)?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(?:on|in)\\s+(?:the\\s+)?(?:google\\s+)?maps?$", RegexOption.IGNORE_CASE), "")
                .trim()
            return t.ifBlank { said.trim() }
        }

        /**
         * "add dentist to my calendar tomorrow at 4 pm", "remind me to call Ravi at 6 pm": a title and a start time, or null when the
         * sentence has no event in it. With no time of day the event is at 9:00; a time already past today moves to tomorrow.
         */
        fun eventFromWords(said: String, now: Long = System.currentTimeMillis()): Pair<String, Long>? {
            val s = said.trim()
            val i = RegexOption.IGNORE_CASE
            var title = Regex("(?:add|create|schedule|put|set up|book)\\s+(?:an?\\s+|the\\s+)?(.+?)\\s+(?:to|on|in|into)\\s+(?:my\\s+|the\\s+)?calendar", i).find(s)?.groupValues?.get(1)
                ?: Regex("remind me\\s+(?:to|about)\\s+(.+)", i).find(s)?.groupValues?.get(1)
                ?: Regex("^(?:schedule\\s+)?(?:an?\\s+)?(.+?)\\s+(?:at|on|for)\\s+\\d", i).find(s)?.groupValues?.get(1)
                ?: return null
            title = title
                .replace(Regex("\\b(?:day after tomorrow|tomorrow|today|tonight|next week|(?:on\\s+)?(?:sunday|monday|tuesday|wednesday|thursday|friday|saturday))\\b", i), " ")
                .replace(Regex("(?:at\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)", i), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (title.isBlank()) return null
            title = title.replaceFirstChar { it.uppercase() }
            val tm = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)", i).find(s) ?: Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\b", i).find(s)
            var hour = 9
            var min = 0
            if (tm != null) {
                hour = tm.groupValues[1].toInt().coerceIn(0, 23)
                min = tm.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59)
                val mer = tm.groupValues.getOrElse(3) { "" }.replace(".", "").lowercase()
                if (mer == "pm" && hour < 12) hour += 12 else if (mer == "am" && hour == 12) hour = 0
            }
            val base = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            var start = dayFromWords(s, base.timeInMillis, now)
            if (start < now) start += 86_400_000L
            return title to start
        }

        private val WORDS =mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30)

        private val TRIGGER = Regex(
            "\\b(flash ?light|torch|wi-?fi|bluetooth|set (an? )?(alarm|timer)|alarm for|timer for|add .* to (my |the )?calendar|calendar event|open (my |the )?(calendar|clock|alarms)|(show|check) (me )?(my )?calendar|what'?s on my calendar|schedule .* (at|on|for|tomorrow)|remind me|appointment at|meeting at|" +
                "create (a )?contact|save (a )?contact|add (a )?contact|new contact|(send|write|compose) (an? )?(e-?mail|mail)|e-?mail \\w+ (about|saying|that)|" +
                "show .* on (the )?maps?|open maps?|navigate to|directions to|" +
                "open (my )?(photos?|gallery|pictures|albums?)|show (me )?(my )?(photos?|gallery|pictures)|" +
                "(?:please |can you |could you |i want to |let'?s )?(call|dial|ring) (him|her|them|my|the|mom|dad|\\+?\\d)|send (an? )?(text|message|sms)|text \\w+ (that|saying))",
            RegexOption.IGNORE_CASE
        )
    }
}

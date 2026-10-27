package com.smriti.app

import com.smriti.app.privacy.TextScrubber
import com.smriti.app.query.Intent
import com.smriti.app.query.IntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class IntentParserTest {

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 9, 17, 30, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun live() {
        assertEquals(Intent.Live, IntentParser.parse("What do you see?", now))
        assertEquals(Intent.Live, IntentParser.parse("what's in front of me", now))
    }

    @Test fun forget() {
        assertEquals(Intent.Forget(5), IntentParser.parse("forget the last 5 minutes", now))
        assertEquals(Intent.Forget(60), IntentParser.parse("Forget everything from the last hour", now))
    }

    @Test fun where() {
        assertEquals(Intent.Where("keys"), IntentParser.parse("Where are my keys?", now))
        assertEquals(Intent.Where("charger"), IntentParser.parse("where did I leave my charger", now))
        assertEquals(Intent.Where("water bottle"), IntentParser.parse("find the water bottle", now))
    }

    @Test fun lastTimePhrases() {
        assertEquals(Intent.Where("bottle"), IntentParser.parse("show me the last time you saw a bottle", now))
        assertEquals(Intent.Where("laptop"), IntentParser.parse("when was the last time you used my laptop", now))
        assertEquals(Intent.Where("wallet"), IntentParser.parse("when did you last see my wallet", now))
    }

    @Test fun whereVariants() {
        assertEquals(Intent.Where("pills"), IntentParser.parse("where did you see my pills", now))
        assertEquals(Intent.Where("pills"), IntentParser.parse("Where did I keep my pills?", now))
        assertEquals(Intent.Where("wallet"), IntentParser.parse("when did you last see my wallet", now))
        assertEquals(Intent.Live, IntentParser.parse("tell me what you see", now))
        assertEquals(Intent.Live, IntentParser.parse("what am I looking at", now))
    }

    @Test fun highlightAndPlace() {
        assertEquals(Intent.Highlight, IntentParser.parse("what was the most important moment", now))
        assertEquals(Intent.InPlace("kitchen"), IntentParser.parse("what did you see in the kitchen", now))
        assertEquals(Intent.Live, IntentParser.parse("what is in front of me", now))
    }

    @Test fun spokenSentences() {
        assertEquals(Intent.Where("keyboard"), IntentParser.parse("Can you tell me where my keyboard is?", now))
        assertEquals(Intent.Where("keyboard"), IntentParser.parse("hey Smriti, where is my keyboard", now))
        assertEquals(Intent.Where("keyboard"), IntentParser.parse("do you know where I kept my keyboard", now))
        assertEquals(Intent.Where("mouse"), IntentParser.parse("I lost my mouse", now))
        assertEquals(Intent.Where("mouse"), IntentParser.parse("did you see my mouse", now))
        assertEquals(Intent.Where("keyboard"), IntentParser.parse("show me my keyboard", now))
        assertEquals(Intent.Where("phone"), IntentParser.parse("where's my phone", now))
        assertEquals(Intent.Live, IntentParser.parse("what are you looking at", now))
        assertEquals(Intent.Live, IntentParser.parse("tell me about this room", now))
        assertEquals(Intent.Live, IntentParser.parse("hey smriti what do you see right now", now))
        assertEquals(Intent.Help, IntentParser.parse("hello", now))
        val r = IntentParser.parse("what did I do in the last five minutes", now) as Intent.Range
        assertEquals(now - 5 * 60_000L, r.fromMs)
        assertTrue(IntentParser.parse("how many things did you see", now) is Intent.Range)
    }

    @Test fun lastN() {
        val r = IntentParser.parse("what happened in the last 10 minutes", now) as Intent.Range
        assertEquals(now - 10 * 60_000L, r.fromMs)
        assertEquals(now, r.toMs)
    }

    @Test fun atTime() {
        val r = IntentParser.parse("what was I doing at 4 pm", now) as Intent.Range
        val centre = (r.fromMs + r.toMs) / 2
        val cal = Calendar.getInstance().apply { timeInMillis = centre }
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun lateEveningTimeAskedJustAfterMidnight() {
        val after = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 0, 15, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val r = IntentParser.parse("what was I doing at 11:30 pm", after) as Intent.Range
        val cal = Calendar.getInstance().apply { timeInMillis = (r.fromMs + r.toMs) / 2 }
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(9, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun fallsBackToSearch() {
        assertTrue(IntentParser.parse("red notebook", now) is Intent.Search)
    }

    @Test fun scrubberRemovesIdentifiers() {
        val out = TextScrubber.scrub("Call 98450 12345 or mail a.b@x.com, car KA01AB1234")
        assertFalse(out.contains("98450"))
        assertFalse(out.contains("@"))
        assertFalse(out.contains("KA01AB1234"))
    }
}

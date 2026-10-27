package com.smriti.app

import com.smriti.app.ai.Actions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ActionsDateTest {

    // Saturday 10 October 2026, 12:40
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 12, 40, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun modelSaid(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun parts(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { Triple(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

    @Test fun tomorrowKeepsTheTimeButFixesTheDay() {
        // the model answered Friday 16 October for "tomorrow at 4 pm"
        val r = Actions.dayFromWords("add dentist to my calendar tomorrow at 4 pm", modelSaid(2026, Calendar.OCTOBER, 16, 16, 0), now)
        assertEquals(Triple(11, 16, 0), parts(r))
    }

    @Test fun weekdayNameGivesTheNextOne() {
        val r = Actions.dayFromWords("meeting on wednesday at 9 am", modelSaid(2026, Calendar.OCTOBER, 1, 9, 0), now)
        assertEquals(Triple(14, 9, 0), parts(r))
    }

    @Test fun noDayWordsKeepsTheModelsDate() {
        val m = modelSaid(2026, Calendar.NOVEMBER, 3, 10, 30)
        assertEquals(m, Actions.dayFromWords("lunch with Ravi at 10:30", m, now))
    }

    @Test fun calendarSentenceGivesTitleAndTime() {
        val (title, start) = Actions.eventFromWords("add dentist to my calendar tomorrow at 4 pm", now)!!
        assertEquals("Dentist", title)
        assertEquals(Triple(11, 16, 0), parts(start))
    }

    @Test fun remindMeGivesTitleAndTodaysTime() {
        val (title, start) = Actions.eventFromWords("remind me to call Ravi at 6 pm", now)!!
        assertEquals("Call Ravi", title)
        assertEquals(Triple(10, 18, 0), parts(start))
    }

    @Test fun aTimePastTodayMovesToTomorrow() {
        val (_, start) = Actions.eventFromWords("add gym to my calendar at 7 am", now)!!
        assertEquals(Triple(11, 7, 0), parts(start))
    }

    @Test fun noEventInTheSentence() {
        org.junit.Assert.assertNull(Actions.eventFromWords("what is the weather", now))
    }

    @Test fun placeNamesAreCleaned() {
        assertEquals("Marina Beach", Actions.placeFromWords("show Marina Beach on the map"))
        assertEquals("chemist", Actions.placeFromWords("navigate to the chemist"))
        assertEquals("nearest petrol pump", Actions.placeFromWords("open maps and find nearest petrol pump").removePrefix("maps and find "))
        assertEquals("T Nagar", Actions.placeFromWords("directions to T Nagar"))
    }
}

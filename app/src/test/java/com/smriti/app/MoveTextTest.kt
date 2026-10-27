package com.smriti.app

import com.smriti.app.move.Geo
import com.smriti.app.move.LatLon
import com.smriti.app.move.MoveIntent
import com.smriti.app.move.MoveIntentParser
import com.smriti.app.move.Pt
import com.smriti.app.move.RouteText
import com.smriti.app.move.WifiPrint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveTextTest {
    @Test fun goingHomePhrases() {
        for (s in listOf("Take me home", "guide me home please", "lead me home", "I want to go home", "which way is home", "How do I get home", "I am lost", "I'm lost", "I don't know the way", "help me get home", "where is home"))
            assertEquals(s, MoveIntent.GoHome, MoveIntentParser.parse(s))
    }

    @Test fun startingAWalk() {
        for (s in listOf("I am going out", "I'm going out now", "start my walk", "going for a walk", "record my walk"))
            assertEquals(s, MoveIntent.StartTrip, MoveIntentParser.parse(s))
    }

    @Test fun stopping() {
        for (s in listOf("stop guiding", "stop the guide", "I am home now", "I'm home", "I have reached"))
            assertEquals(s, MoveIntent.Stop, MoveIntentParser.parse(s))
    }

    @Test fun aPlaceByName() {
        assertEquals(MoveIntent.ToPlace("chemist"), MoveIntentParser.parse("take me to the chemist"))
        assertEquals(MoveIntent.ToPlace("my sister's house".removePrefix("my ")), MoveIntentParser.parse("guide me to my sister's house"))
    }

    @Test fun otherSentencesAreNotWalks() {
        for (s in listOf("what do you see", "where is my phone", "where did I keep my keys", "show me the keys", "what is the weather"))
            assertNull(s, MoveIntentParser.parse(s))
    }

    @Test fun hindiRequestsAreUnderstood() {
        for (s in listOf("मुझे घर ले चलो", "घर ले चलिए", "घर का रास्ता बताओ", "मुझे घर जाना है", "मैं खो गया हूँ", "मैं खो गई हूँ", "ghar le chalo", "mujhe ghar jana hai", "ghar ka rasta batao"))
            assertEquals(s, MoveIntent.GoHome, MoveIntentParser.parse(s))
        for (s in listOf("मैं बाहर जा रहा हूँ", "मैं बाहर जा रही हूँ", "main bahar ja raha hoon"))
            assertEquals(s, MoveIntent.StartTrip, MoveIntentParser.parse(s))
        for (s in listOf("गाइड बंद करो", "guide band karo"))
            assertEquals(s, MoveIntent.Stop, MoveIntentParser.parse(s))
        assertNull(MoveIntentParser.parse("आज मौसम कैसा है"))
    }

    @Test fun aSlipOfTheSpeechRecogniserStillMeansHome() {
        assertEquals(MoveIntent.GoHome, MoveIntentParser.parse("Stay at me home"))
        assertEquals(MoveIntent.GoHome, MoveIntentParser.parse("send me home"))
        for (s in listOf("show me home screen", "show me home videos", "can you tell me about the weather at home today please")) assertNull(s, MoveIntentParser.parse(s))
        // a long sentence that happens to contain "me home" is a conversation, not a request
        assertNull(MoveIntentParser.parse("my friend told me home prices are going up this year in the city"))
    }

    @Test fun curlyApostrophesAreFine() {
        assertEquals(MoveIntent.GoHome, MoveIntentParser.parse("I’m lost"))
        assertEquals(MoveIntent.StartTrip, MoveIntentParser.parse("I’m going out"))
    }

    @Test fun lostWinsOverStop() {
        assertEquals(MoveIntent.GoHome, MoveIntentParser.parse("I am lost, I am not home"))
    }

    // ---- Wi-Fi places ---------------------------------------------------------------------------------------------
    @Test fun networksAreStoredLowerCaseDistinctAndAtMostEight() {
        val s = WifiPrint.encode((1..12).map { "AA:BB:CC:00:00:%02X".format(it) } + "aa:bb:cc:00:00:01")
        assertEquals(8, WifiPrint.decode(s).size)
        assertTrue(s == s.lowercase())
    }

    @Test fun samePlaceHearsMostOfTheSameNetworks() {
        val home = "a1,a2,a3,a4,a5,a6"
        assertTrue(WifiPrint.isHere(home, "a1,a3,a4,x9"))
        assertFalse(WifiPrint.isHere(home, "a1,z1,z2,z3"))
        assertFalse(WifiPrint.isHere("a1", "a1"))                       // one network says too little
        assertEquals(1.0, WifiPrint.similarity(home, home), 1e-9)
        assertEquals(0.0, WifiPrint.similarity(home, ""), 1e-9)
    }

    // ---- recorded routes ------------------------------------------------------------------------------------------
    @Test fun aRouteSurvivesBeingStoredAsText() {
        val pts = listOf(Pt(0.0, 0.0), Pt(60.04, -1.26), Pt(60.0, 40.5))
        val back = RouteText.decode(RouteText.encode(pts))
        assertEquals(3, back.size)
        assertEquals(60.0, back[1].x, 0.06); assertEquals(-1.3, back[1].y, 0.06)
        assertTrue(RouteText.decode("garbage;1,2;3").size == 1)
    }

    @Test fun aRouteCanBeShownAroundAnotherStart() {
        val a = LatLon(12.97, 77.59)
        val b = Geo.fromLocal(a, 30.0, 40.0)              // the user now stands 30 m east and 40 m north of where the walk began
        val moved = RouteText.reframe(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), a, b)
        assertEquals(-30.0, moved[0].x, 0.05); assertEquals(-40.0, moved[0].y, 0.05)
        assertEquals(70.0, moved[1].x, 0.05)
    }
}

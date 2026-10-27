package com.smriti.app

import com.smriti.app.ai.GuideIntent
import com.smriti.app.ai.GuideMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideMathTest {
    @Test fun gridIsNineOverlappingHalves() {
        val t = GuideMath.tiles()
        assertEquals(9, t.size)
        assertEquals(0f, t[0].left, 0.001f); assertEquals(0.5f, t[0].right, 0.001f)
        assertEquals(0.5f, t[8].left, 0.001f); assertEquals(1f, t[8].right, 0.001f)
        assertEquals(0.5f, t[4].cx, 0.001f)
    }

    // scores measured on a real picture: a white charger in the top left corner
    private val charger = listOf(0.449f, 0.327f, 0.287f, 0.252f, 0.343f, 0.308f, 0.169f, 0.205f, 0.251f)
    private val bottleAbsent = listOf(0.162f, 0.177f, 0.170f, 0.199f, 0.206f, 0.234f, 0.208f, 0.211f, 0.214f)
    private val cable = listOf(0.378f, 0.358f, 0.343f, 0.399f, 0.462f, 0.502f, 0.216f, 0.243f, 0.412f)

    @Test fun findsTheObjectInTheRightTile() {
        val d = GuideMath.decide(charger)
        assertTrue(d.found)
        assertEquals(0, d.bestTile)
        val b = d.box!!
        assertTrue(b.cx < 0.36f && b.cy < 0.36f)          // top left
        assertEquals("at the top on the left", GuideMath.describe(b))
    }

    @Test fun anAbsentObjectIsNotFound() {
        assertFalse(GuideMath.decide(bottleAbsent).found)
    }

    @Test fun blendsNeighbouringTiles() {
        val d = GuideMath.decide(cable)
        assertTrue(d.found)
        assertTrue(d.box!!.cx > 0.5f)                      // the cable runs through the right side
    }

    @Test fun wrongNumberOfScoresIsNotFound() {
        assertFalse(GuideMath.decide(listOf(0.9f, 0.1f)).found)
    }

    @Test fun turnsTowardsTheObject() {
        assertEquals("Turn a little left", GuideMath.turn(GuideMath.Box(0.05f, 0.4f, 0.4f, 0.6f)))
        assertEquals("Turn a little right", GuideMath.turn(GuideMath.Box(0.6f, 0.4f, 0.95f, 0.6f)))
        assertEquals("Tilt up a little", GuideMath.turn(GuideMath.Box(0.4f, 0.0f, 0.6f, 0.3f)))
        assertNull(GuideMath.turn(GuideMath.Box(0.4f, 0.4f, 0.6f, 0.6f)))
    }

    @Test fun hintNamesTheObject() {
        assertTrue(GuideMath.hint("keys", GuideMath.Box(0.05f, 0.4f, 0.4f, 0.6f)).contains("keys is on the left"))
        assertEquals("Hold still: keys is right here", GuideMath.hint("keys", GuideMath.Box(0.4f, 0.4f, 0.6f, 0.6f)))
    }

    @Test fun parsesSpokenRequests() {
        assertEquals("steel bottle", GuideIntent.parse("Where is my steel bottle?"))
        assertEquals("keys", GuideIntent.parse("help me find the keys"))
        assertEquals("red bag", GuideIntent.parse("can you please find a red bag right now"))
        assertEquals("pills", GuideIntent.parse("Show me my pills"))
        assertEquals("glasses", GuideIntent.parse("okay, look for my glasses"))
        assertEquals("charger", GuideIntent.parse("where's the charger here"))
    }

    @Test fun otherQuestionsAreNotGuideRequests() {
        assertNull(GuideIntent.parse("Where did I keep my laptop?"))
        assertNull(GuideIntent.parse("where was my bag yesterday"))
        assertNull(GuideIntent.parse("what do you see now"))
        assertNull(GuideIntent.parse("find out the weather"))
        assertNull(GuideIntent.parse("show me something"))
        assertNull(GuideIntent.parse("how many people are here"))
    }

    @Test fun parsesWhereWasItQuestions() {
        assertEquals("bottle", GuideIntent.parseSeen("Where did I see my bottle?"))
        assertEquals("keys", GuideIntent.parseSeen("where did I leave my keys"))
        assertEquals("steel bottle", GuideIntent.parseSeen("okay where did I last see the steel bottle"))
        assertEquals("bag", GuideIntent.parseSeen("where was my bag yesterday"))
        assertEquals("laptop", GuideIntent.parseSeen("Where did I keep my laptop?"))
        assertEquals("charger", GuideIntent.parseSeen("where have I put the charger"))
    }

    @Test fun whereWasItIgnoresOtherQuestions() {
        assertNull(GuideIntent.parseSeen("where is my bottle"))
        assertNull(GuideIntent.parseSeen("what did I do yesterday"))
        assertNull(GuideIntent.parseSeen("where did I go this morning"))
        assertNull(GuideIntent.parseSeen("when did I eat"))
        assertNull(GuideIntent.parseSeen("how many people were there"))
    }

    @Test fun stopPhrases() {
        assertTrue(GuideIntent.isStop("stop looking"))
        assertTrue(GuideIntent.isStop("Never mind."))
        assertTrue(GuideIntent.isStop("I found it"))
        assertFalse(GuideIntent.isStop("where is my phone"))
        assertNotNull(GuideIntent.parse("find my phone"))
    }
}

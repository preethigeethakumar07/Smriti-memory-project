package com.smriti.app

import com.smriti.app.ai.Inventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryTest {
    @Test fun parseCleansListAndDropsSentences() {
        val got = Inventory.parse("- Laptop, 3 books\n* steel bottle; This is a very long sentence about nothing at all, Laptop, text: SMRITI 1.3")
        assertEquals(listOf("laptop", "3 books", "steel bottle", "text: smriti 1.3"), got)
    }

    @Test fun parseOfNothingIsEmpty() {
        assertTrue(Inventory.parse(null).isEmpty())
        assertTrue(Inventory.parse("None").isEmpty())
    }

    @Test fun mergeKeepsDetectorTextAndAddsObjects() {
        val c = Inventory.merge("a desk indoors", listOf("laptop", "mug"))
        assertEquals("a desk indoors Objects: laptop, mug", c)
        // merging again replaces the old inventory
        assertEquals("a desk indoors Objects: key", Inventory.merge(c, listOf("key")))
        assertEquals(listOf("laptop", "mug"), Inventory.objectsOf(c))
    }

    @Test fun mergeRespectsLimit() {
        val many = List(60) { "object number $it" }
        assertTrue(Inventory.merge("caption", many, 200).length <= 200)
    }

    @Test fun keywordsDropFillerWordsAndPlurals() {
        assertEquals(listOf("steel", "bottle"), Inventory.keywords("Where is my steel bottle?"))
        assertEquals(listOf("key"), Inventory.keywords("where are my keys"))
        assertEquals(listOf("battery"), Inventory.keywords("did you see batteries"))
    }

    @Test fun matchNeedsEveryKeyword() {
        val c = "a room Objects: steel bottle, laptop, 3 books"
        assertTrue(Inventory.matches("where is my steel bottle", c))
        assertTrue(Inventory.matches("any books", c))
        assertFalse(Inventory.matches("where is my water bottle", c))
        assertFalse(Inventory.matches("where is my pill", c))
        assertFalse(Inventory.matches("where is my laptop", "a room with no list"))
    }

    @Test fun wantsListRecognisesWholeSceneQuestions() {
        assertTrue(Inventory.wantsList("What objects do you see?"))
        assertTrue(Inventory.wantsList("list everything on the table"))
        assertTrue(Inventory.wantsList("what do you see"))
        assertFalse(Inventory.wantsList("is there a bottle"))
        assertFalse(Inventory.wantsList("read this label"))
    }
}

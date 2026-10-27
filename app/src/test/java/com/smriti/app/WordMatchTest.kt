package com.smriti.app

import com.smriti.app.memory.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordMatchTest {

    private fun match(text: String, query: String) =
        MemoryRepository.itemMatches(text, MemoryRepository.tokens(query))

    @Test fun keyDoesNotMatchKeyboard() {
        assertFalse(match("keyboard", "keys"))
        assertFalse(match("I see a mobile phone. Visible text: keyboard shortcuts", "key"))
    }

    @Test fun keysMatchesKeyAndKeys() {
        assertTrue(match("key", "keys"))
        assertTrue(match("keys", "my keys"))
        assertTrue(match("a set of keys on a table", "keys"))
    }

    @Test fun multiWordNeedsAllWords() {
        assertTrue(match("water bottle", "the water bottle"))
        assertFalse(match("bottle", "water bottle"))
        assertFalse(match("water", "water bottle"))
    }

    @Test fun emptyNeverMatches() {
        assertFalse(match("anything", ""))
        assertFalse(match("anything", "the my"))
    }

    @Test fun tokenisingDropsStopWordsAndStems() {
        assertEquals(listOf("water", "bottle"), MemoryRepository.tokens("Where is my water bottles"))
    }
}

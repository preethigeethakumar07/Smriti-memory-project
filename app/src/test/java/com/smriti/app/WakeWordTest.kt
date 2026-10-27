package com.smriti.app

import com.smriti.app.live.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {
    @Test fun plainWakePhrase() {
        assertEquals("", WakeWord.match("hey smriti"))
        assertEquals("", WakeWord.match("Hey Smriti"))
    }

    @Test fun questionAfterTheWakePhrase() {
        assertEquals("where is my mouse", WakeWord.match("hey smriti where is my mouse"))
        assertEquals("what do you see", WakeWord.match("ok smrithi, what do you see"))
    }

    @Test fun mishearingsStillWake() {
        assertEquals("", WakeWord.match("hey smitty"))
        assertEquals("", WakeWord.match("hey smirti"))
        assertEquals("find my keys", WakeWord.match("hi smritee find my keys"))
    }

    @Test fun ordinaryTalkDoesNotWake() {
        assertNull(WakeWord.match("I love this memory of the trip"))
        assertNull(WakeWord.match("where is my mouse"))
        assertNull(WakeWord.match("the smriti app is nice"))   // name not at the start and not after a greeting
    }
}

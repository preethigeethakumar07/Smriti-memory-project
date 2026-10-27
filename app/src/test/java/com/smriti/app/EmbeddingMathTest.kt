package com.smriti.app

import com.smriti.app.ai.VisualMemory
import com.smriti.app.embed.GemmaEmbedder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingMathTest {

    @Test fun normalisedVectorsHaveUnitLength() {
        val v = GemmaEmbedder.normalise(floatArrayOf(3f, 4f, 0f))
        assertEquals(1f, GemmaEmbedder.cosine(v, v), 1e-5f)
        assertEquals(0.6f, v[0], 1e-5f)
    }

    @Test fun cosineSeparatesSimilarFromUnrelated() {
        val a = GemmaEmbedder.normalise(floatArrayOf(1f, 1f, 0f))
        val b = GemmaEmbedder.normalise(floatArrayOf(1f, 0.9f, 0.1f))
        val c = GemmaEmbedder.normalise(floatArrayOf(0f, 0f, 1f))
        assertTrue(GemmaEmbedder.cosine(a, b) > 0.95f)
        assertTrue(GemmaEmbedder.cosine(a, c) < 0.05f)
    }

    @Test fun differentLengthsNeverMatch() {
        assertEquals(0f, GemmaEmbedder.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)), 0f)
    }

    @Test fun vectorSurvivesStorage() {
        val v = GemmaEmbedder.normalise(FloatArray(768) { (it % 7) - 3f })
        val back = VisualMemory.unpack(VisualMemory.pack(v))
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 0f)
    }
}

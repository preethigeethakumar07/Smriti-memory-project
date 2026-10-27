package com.smriti.app

import com.smriti.app.scene.NoteRow
import com.smriti.app.scene.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBuilderTest {
    private fun row(start: Long, end: Long, objs: Set<String>, zone: String = "somewhere", motion: Float = 0.1f, density: Float = 0.1f) =
        NoteRow(start, end, objs, zone, motion, density, 0, false)

    @Test fun sameSurroundingsMergeIntoOneScene() {
        val scenes = SceneBuilder.build(
            listOf(
                row(0, 20_000, setOf("keyboard", "mouse", "tv")),
                row(30_000, 60_000, setOf("keyboard", "mouse")),
                row(70_000, 90_000, setOf("keyboard", "tv"))
            )
        )
        assertEquals(1, scenes.size)
        assertEquals(0L, scenes[0].start)
        assertEquals(90_000L, scenes[0].end)
        assertTrue("mouse" in scenes[0].objects)
    }

    @Test fun differentThingsOrALongGapStartANewScene() {
        val scenes = SceneBuilder.build(
            listOf(
                row(0, 20_000, setOf("keyboard", "mouse")),
                row(30_000, 50_000, setOf("bed", "pillow")),               // nothing in common
                row(400_000, 420_000, setOf("bed", "pillow"))              // more than two minutes later
            )
        )
        assertEquals(3, scenes.size)
    }

    @Test fun placeChangeStartsANewScene() {
        val scenes = SceneBuilder.build(listOf(row(0, 10_000, setOf("cup"), "kitchen"), row(20_000, 30_000, setOf("cup"), "bedroom")))
        assertEquals(2, scenes.size)
    }

    @Test fun busyInteractiveScenesRankHigher() {
        val calm = SceneBuilder.build(listOf(row(0, 20_000, setOf("wall"), motion = 0.02f, density = 0f))).single()
        val busy = SceneBuilder.build(listOf(row(0, 120_000, setOf("cup", "table", "laptop", "person", "book"), motion = 0.8f, density = 0.9f))).single()
        assertTrue(busy.importance > calm.importance)
        assertEquals("High", SceneBuilder.level(busy.motion))
        assertEquals("Low", SceneBuilder.level(calm.motion))
    }

    @Test fun titleNamesPlaceAndObjects() {
        val s = SceneBuilder.build(listOf(row(0, 10_000, setOf("keyboard", "mouse"), "workspace"))).single()
        assertTrue(s.title.startsWith("Workspace"))
    }
}
